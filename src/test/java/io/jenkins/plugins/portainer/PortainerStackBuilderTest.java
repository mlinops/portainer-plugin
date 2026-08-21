package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.Stapler;
import org.springframework.security.access.AccessDeniedException;
import net.sf.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerStackBuilderTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicBoolean stacksEmpty = new AtomicBoolean(false);
    private final AtomicBoolean endpointMissing = new AtomicBoolean(false);
    private final AtomicBoolean createCalled = new AtomicBoolean(false);

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        stacksEmpty.set(false);
        endpointMissing.set(false);
        createCalled.set(false);
        lastPath.set(null);
        lastMethod.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastMethod.set(exchange.getRequestMethod());
            byte[] raw = exchange.getRequestBody().readAllBytes();
            lastBody.set(raw.length == 0 ? "" : new String(raw, StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            if ("/api/status".equals(path)) {
                respond(exchange, 200, "{\"Version\":\"2.39.3\"}");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+$")) {
                if (endpointMissing.get()) {
                    respond(exchange, 404, "{\"message\":\"Object not found inside the database\"}");
                } else {
                    respond(exchange, 200, "{\"Id\":1,\"Name\":\"local\"}");
                }
                return;
            }
            if ("/api/endpoints".equals(path)) {
                respond(exchange, 200, "[{\"Id\":1,\"Name\":\"local\"}]");
                return;
            }
            if (path.endsWith("/api/stacks/create/standalone/repository")) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":11,\"Name\":\"demo\"}");
                return;
            }
            if (path.endsWith("/api/stacks/create/standalone/string")) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":11,\"Name\":\"demo\"}");
                return;
            }
            if (path.contains("/docker/swarm")) {
                respond(exchange, 200, "{\"ID\":\"swarm-1\"}");
                return;
            }
            if (path.endsWith("/api/stacks/create/swarm/repository")) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":12,\"Name\":\"demo\"}");
                return;
            }
            if (path.endsWith("/api/stacks/create/swarm/string")) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":12,\"Name\":\"demo\"}");
                return;
            }
            if (path.contains("/git/redeploy")) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":11,\"Name\":\"demo\"}");
                return;
            }
            // PUT /api/stacks/{id} file content update (not git redeploy)
            if (path != null && path.matches("/api/stacks/\\d+$")
                    && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":11,\"Name\":\"demo\"}");
                return;
            }
            if ("/api/stacks".equals(path)) {
                if (stacksEmpty.get()) {
                    respond(exchange, 200, "[]");
                } else {
                    respond(exchange, 200, "[{\"Id\":11,\"Name\":\"demo\",\"EndpointId\":1}]");
                }
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void freestyle_deploysWhenStackMissing_andConfigRoundTrip(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setComposeFilePath("docker-compose.yml");
        step.setEnv("IMAGE_TAG=1");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
        assertEquals("1", loaded.getEndpointId());
        assertEquals("compose", loaded.getStackType());
        assertEquals("demo", loaded.getStackName());
        assertEquals("https://gitlab.example/group/stack.git", loaded.getRepositoryUrl());
        assertEquals("docker-compose.yml", loaded.getComposeFilePath());
        assertEquals(PortainerStackBuilder.SOURCE_REPOSITORY, loaded.getStackSource());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("[DEBUG]", build);
        assertTrue(lastPath.get().contains("/standalone/repository"));
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
        assertTrue(loaded.isValidateOnly());

        jenkins.buildAndAssertSuccess(project);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
        // validateOnly looks up the stack after preflight, so last path is /api/stacks
        assertTrue(lastPath.get() != null
                && (lastPath.get().equals("/api/stacks")
                        || lastPath.get().matches(".*/api/endpoints/\\d+$")));
    }

    @Test
    public void pipeline_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "stack-validate-only");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'demo',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    validateOnly: true
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void pipeline_withoutNode_validateOnly(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);
        assertFalse(new PortainerStackBuilder("1", "compose", "demo").requiresWorkspace());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "stack-no-node");
        job.setDefinition(new CpsFlowDefinition(
                """
                portainerStack(
                  endpointId: '1',
                  stackType: 'compose',
                  stackName: 'demo',
                  repositoryUrl: 'https://gitlab.example/group/stack.git',
                  validateOnly: true
                )
                """,
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void freestyle_validateOnly_yamlInvalid_failsWithoutMutate(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        step.setStackFileContent("services: [\n");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Invalid Compose YAML", build);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
    }

    /**
     * Freestyle {@code f:radioBlock inline="true"} binds Portainer mode strings at the top level.
     * Vault is a nested {@link VaultConnection} ({@code f:dropdownDescriptorSelector}).
     */
    @Test
    public void stapler_bindsVaultManualNested(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("stapler-class", PortainerStackBuilder.class.getName());
            json.put("$class", PortainerStackBuilder.class.getName());
            json.put("endpointId", "245");
            json.put("stackType", "compose");
            json.put("stackName", "test-nginx");
            json.put("repositoryUrl", "https://gitlab.example/group/stack.git");
            json.put("composeFilePath", "compose.yml");
            json.put("gitCredentialsId", "gitlab_api_token");
            json.put("repositoryReferenceName", "refs/heads/main");
            json.put("env", "TEST_ENV=1");
            json.put("prune", false);
            json.put("repullImageAndRedeploy", true);
            json.put("portainerConnectionMode", "inherit");
            json.put("vault", vaultJson(VaultManual.class, "https://vault.example:8200", "jenkins-portainer-simple", "basic", "applications"));

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
        assertInstanceOf(VaultManual.class, step.getVault());
        assertEquals("https://vault.example:8200", step.getVault().getVaultUrl());
        assertEquals("jenkins-portainer-simple", step.getVault().getVaultAppRoleCredentialsId());
        assertEquals("245", step.getEndpointId());
        assertEquals("test-nginx", step.getStackName());
        assertEquals("basic", step.getVault().getVaultPath());
        assertEquals("applications", step.getVault().getVaultMount());
    }

    @Test
    public void stapler_inlineRadio_bindsPortainerManual(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("endpointId", "1");
            json.put("stackType", "compose");
            json.put("stackName", "demo");
            json.put("repositoryUrl", "https://gitlab.example/group/stack.git");
            json.put("portainerConnectionMode", "manual");
            json.put("portainerUrl", "https://portainer.example:9443");
            json.put("portainerCredentialsId", "portainer-api-key");
            json.put("vault", vaultJson(VaultInherit.class, null, null, null, null));

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertEquals(PortainerStackBuilder.MODE_MANUAL, step.getPortainerConnectionMode());
        assertEquals("https://portainer.example:9443", step.getPortainerUrl());
        assertEquals("portainer-api-key", step.getPortainerCredentialsId());
        assertInstanceOf(VaultInherit.class, step.getVault());
    }

    @Test
    public void stapler_bindsVaultNone(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("endpointId", "1");
            json.put("stackType", "compose");
            json.put("stackName", "demo");
            json.put("repositoryUrl", "https://gitlab.example/group/stack.git");
            json.put("portainerConnectionMode", "inherit");
            json.put("vault", vaultJson(VaultNone.class, null, null, null, null));

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertInstanceOf(VaultNone.class, step.getVault());
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
    }

    @Test
    public void xstream_legacyVaultNone_staysNone(JenkinsRule jenkins) throws Exception {
        try (InputStream in = PortainerStackBuilderTest.class.getResourceAsStream("stack-legacy-vault-none.xml")) {
            assertNotNull(in);
            PortainerStackBuilder step = (PortainerStackBuilder) hudson.model.Items.XSTREAM2.fromXML(in);
            assertInstanceOf(VaultNone.class, step.getVault());
            assertFalse(hudson.model.Items.XSTREAM2.toXML(step).contains("<vaultConnectionMode>"));
        }
    }

    @Test
    public void freestyle_vaultNone_skipsVaultAndDeploys(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVault(new VaultNone());
        step.setEnv("IMAGE_TAG=1");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
        assertInstanceOf(VaultNone.class, loaded.getVault());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("Preflight check of Vault", build);
        assertTrue(createCalled.get());
        assertTrue(lastPath.get().contains("/standalone/repository"));
    }

    @Test
    public void freestyle_redeploysWhenStackExists(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(false);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setPrune(true);
        step.setRepullImageAndRedeploy(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(lastPath.get().contains("/git/redeploy"));
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_yamlCreate_whenStackMissing_noYamlBodyInLog(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        String secretMarker = "YAML_SECRET_SHOULD_NOT_LOG_abc123";
        String yaml = "services:\n  web:\n    image: nginx:alpine\n    environment:\n      - TOKEN="
                + secretMarker + "\n";

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        step.setStackFileContent(yaml);
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
        assertEquals(PortainerStackBuilder.SOURCE_YAML, loaded.getStackSource());
        assertTrue(loaded.getStackFileContent() != null && loaded.getStackFileContent().contains("nginx"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains(secretMarker, build);
        jenkins.assertLogNotContains("TOKEN=", build);
        assertTrue(lastPath.get().contains("/standalone/string"));
        assertTrue(lastBody.get().contains("StackFileContent"));
        assertTrue(lastBody.get().contains(secretMarker)); // request body OK; must not appear in console
    }

    @Test
    public void freestyle_yamlUpdate_whenStackExists(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(false);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        step.setStackFileContent("services:\n  web:\n    image: nginx:alpine\n");
        step.setPrune(true);
        step.setRepullImageAndRedeploy(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(lastPath.get().matches(".*/api/stacks/11$") || lastPath.get().endsWith("/api/stacks/11"));
        assertTrue(!lastPath.get().contains("git/redeploy"));
        assertEquals("PUT", lastMethod.get());
        assertTrue(createCalled.get());
        assertTrue(lastBody.get().contains("\"Prune\":true") || lastBody.get().contains("\"Prune\": true"));
    }

    @Test
    public void freestyle_yamlMissingContent_failsBeforePortainer(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        lastPath.set(null);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Stack YAML content is required", build);
        assertNull(lastPath.get());
    }

    @Test
    public void freestyle_invalidInlineYaml_failsBeforePortainer(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        lastPath.set(null);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        step.setStackFileContent("services: [\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Invalid Compose YAML", build);
        assertNull(lastPath.get());
    }

    @Test
    public void stapler_inlineRadio_bindsStackSourceYaml(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("endpointId", "1");
            json.put("stackType", "compose");
            json.put("stackName", "demo");
            json.put("portainerConnectionMode", "inherit");
            json.put("stackSource", "yaml");
            json.put("stackFileContent", "services:\n  web:\n    image: nginx:alpine\n");

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertEquals(PortainerStackBuilder.SOURCE_YAML, step.getStackSource());
        assertTrue(step.getStackFileContent() != null && step.getStackFileContent().contains("nginx"));
    }

    @Test
    public void pipeline_yamlCreate_smoke(JenkinsRule jenkins) throws Exception {
        configurePortainer("default");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "yaml-create");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'demo',
                    stackSource: 'yaml',
                    stackFileContent: '''
                services:
                  web:
                    image: nginx:alpine
                '''
                  )
                }
                """,
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogNotContains("image: nginx:alpine", run);
        assertTrue(lastPath.get().contains("/standalone/string"));
    }

    @Test
    public void freestyle_notConfigured_failsWithoutHttp(JenkinsRule jenkins) throws Exception {
        lastPath.set(null);
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("prod");
        cfg.setPortainerUrl("");
        cfg.setCredentialsId("");
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Portainer is not configured", build);
        assertNull(lastPath.get());
    }

    @Test
    public void pipeline_autoDeployWhenMissing_createsStack(JenkinsRule jenkins)
            throws Exception {
        configurePortainer("Production Portainer");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "deploy-missing");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'demo',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml'
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(lastPath.get().contains("/standalone/repository"));
        assertTrue(createCalled.get());
    }

    @Test
    public void pipeline_rejectsLegacyActionAndInstanceIdParams(JenkinsRule jenkins) throws Exception {
        configurePortainer("Production Portainer");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "deploy-legacy-params");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerStack(
                    action: 'redeploy',
                    instanceId: 'ignored',
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'demo',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml'
                  )
                }
                """,
                true));
        WorkflowRun run = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("action", run);
        jenkins.assertLogContains("instanceId", run);
        assertFalse(
                isStackMutateApi(lastPath.get(), lastMethod.get()),
                "legacy params must not reach stack mutate APIs; lastPath=" + lastPath.get());
    }

    @Test
    public void pipeline_autoRedeploy_smoke(JenkinsRule jenkins) throws Exception {
        configurePortainer("default");
        stacksEmpty.set(false);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'demo',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    prune: true,
                    repullImageAndRedeploy: true
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(lastPath.get().contains("/git/redeploy"));
        assertTrue(createCalled.get());
    }

    @Test
    public void pipeline_verboseLogging_includesHttpPaths(JenkinsRule jenkins) throws Exception {
        configurePortainer("default");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "verbose-deploy");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'demo',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    verboseLogging: true
                  )
                }
                """,
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("[DEBUG] GET /api/", run);
        jenkins.assertLogContains("[DEBUG] Connection=", run);
        assertTrue(lastPath.get().contains("/standalone/repository"));
        assertTrue(createCalled.get());
    }

    @Test
    public void formValidation_stackName(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        FormValidation stackOk = d.doCheckStackName("my-app", project);
        assertEquals(FormValidation.Kind.OK, stackOk.kind);
        FormValidation stackBad = d.doCheckStackName("MyApp", project);
        assertEquals(FormValidation.Kind.ERROR, stackBad.kind);
        assertTrue(stackBad.getMessage().endsWith("."));
        FormValidation stackEmpty = d.doCheckStackName("", project);
        assertEquals(FormValidation.Kind.ERROR, stackEmpty.kind);
        assertEquals("Stack name is required.", stackEmpty.getMessage());
    }

    @Test
    public void formValidation_composePath(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(FormValidation.Kind.OK, d.doCheckComposeFilePath("docker-compose.yml", "repository", project).kind);
        FormValidation composeEmpty = d.doCheckComposeFilePath("", "repository", project);
        assertEquals(FormValidation.Kind.ERROR, composeEmpty.kind);
        assertEquals("Compose file path is required.", composeEmpty.getMessage());
        FormValidation composeTraversal = d.doCheckComposeFilePath("../secret.yml", "repository", project);
        assertEquals(FormValidation.Kind.ERROR, composeTraversal.kind);
        assertTrue(composeTraversal.getMessage().endsWith("."));
        assertEquals(FormValidation.Kind.ERROR, d.doCheckComposeFilePath("/abs/compose.yml", "repository", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckComposeFilePath("", "yaml", project).kind);
    }

    @Test
    public void formValidation_yamlAndRepo(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckStackFileContent("services:\n  web:\n    image: nginx:alpine\n", "yaml", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStackFileContent("", "yaml", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStackFileContent("", "repository", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckRepositoryUrl("", "yaml", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckRepositoryUrl("", "repository", project).kind);
    }

    @Test
    public void formValidation_env(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(FormValidation.Kind.OK, d.doCheckEnv("IMAGE_TAG=1.2.3\n# comment\n", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckEnv("", project).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckEnv("NOEQUALS", project).kind,
                "bare KEY is KEY=${KEY}");
        FormValidation envBadKey = d.doCheckEnv("bad-key=1", project);
        assertEquals(FormValidation.Kind.ERROR, envBadKey.kind);
    }

    @Test
    public void formValidation_stackType(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(FormValidation.Kind.OK, d.doCheckStackType("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStackType("compose", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStackType("k8s", project).kind);
    }

    @Test
    public void doCheckStackName_withoutConfigure_denied(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy()
                        .grant(Jenkins.READ, Item.READ).everywhere().to("viewer"));

        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        try (ACLContext ignored = ACL.as(User.getById("viewer", true))) {
            assertThrows(AccessDeniedException.class, () -> d.doCheckStackName("my-app", project));
        }
    }

    @Test
    public void freestyle_missingCredentials_failsEvenWithUrl(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setCredentialsId("");
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Portainer is not configured", build);
    }

    @Test
    public void freestyle_http500_errorSummaryNoStackFlood(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 500, "{\"message\":\"boom\"}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setPortainerUrl(base);
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("[ERROR] Preflight failed: HTTP 500 - boom", build);
        jenkins.assertLogNotContains("ERROR: Portainer:", build);
        jenkins.assertLogNotContains("at io.jenkins.plugins.portainer.PortainerClient", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_preflight_unreachable_failsBeforeCreate(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setPortainerUrl("http://127.0.0.1:1");
        cfg.setConnectTimeoutMs(500);
        cfg.setReadTimeoutMs(500);
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("[ERROR] Preflight failed", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_preflight_missingEndpoint_failsBeforeCreate(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        endpointMissing.set(true);
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(repoStack("99", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("[ERROR] Preflight failed", build);
        jenkins.assertLogContains("endpoint ID 99", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_invalidWorkspaceYaml_doesNotBlockGitDeploy(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);
        lastPath.set(null);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FilePath workspace = jenkins.jenkins.getWorkspaceFor(project);
        workspace.mkdirs();
        workspace.child("docker-compose.yml").write("services: [\n", "UTF-8");
        project.getBuildersList().add(repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("Invalid Compose YAML", build);
        assertTrue(lastPath.get() != null && lastPath.get().contains("/standalone/repository"));
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_vaultOverlay_usernamePassword_noSecretsInLog(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        HttpServer vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> vaultLoginBody = new AtomicReference<>();
        vault.createContext("/v1/auth/approle/login", exchange -> {
            vaultLoginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"auth\":{\"client_token\":\"hvs.test-token\"}}");
        });
        vault.createContext("/v1/auth/token/lookup-self", exchange ->
                respond(exchange, 200, "{\"data\":{\"display_name\":\"approle\"}}"));
        vault.createContext("/v1/secret/data", exchange -> respond(
                exchange,
                200,
                "{\"data\":{\"data\":{\"IMAGE_TAG\":\"from-vault\",\"DB_PASS\":\"s3cret\"},\"metadata\":{\"version\":1}}}"));
        vault.createContext("/v1/auth/token/revoke-self", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        vault.start();
        String vaultBase = "http://127.0.0.1:" + vault.getAddress().getPort();

        try {
            SystemCredentialsProvider.getInstance().getCredentials().add(
                    new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL,
                            "vault-approle",
                            "Vault AppRole",
                            "role-id-SHOULD-NOT-LOG",
                            "secret-id-SHOULD-NOT-LOG"));
            SystemCredentialsProvider.getInstance().save();

            FreeStyleProject project = jenkins.createFreeStyleProject();
            PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
            step.setEnv("FEATURE_FLAG=true");
            step.setVault(vaultManual(vaultBase, "vault-approle", "myapp/prod"));
            project.getBuildersList().add(step);

            jenkins.configRoundtrip(project);
            PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
            assertEquals("vault-approle", loaded.getVault().getVaultAppRoleCredentialsId());
            assertEquals(vaultBase, loaded.getVault().getVaultUrl());
            assertEquals("myapp/prod", loaded.getVault().getVaultPath());
            assertInstanceOf(VaultManual.class, loaded.getVault());

            FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
            jenkins.assertLogNotContains("role-id-SHOULD-NOT-LOG", build);
            jenkins.assertLogNotContains("secret-id-SHOULD-NOT-LOG", build);
            jenkins.assertLogNotContains("s3cret", build);
            jenkins.assertLogNotContains("hvs.test-token", build);
            jenkins.assertLogNotContains("[DEBUG]", build);
            assertTrue(vaultLoginBody.get() != null && vaultLoginBody.get().contains("role_id"));
            assertTrue(createCalled.get());
            assertTrue(lastPath.get().contains("/standalone/repository"));
        } finally {
            vault.stop(0);
        }
    }

    @Test
    public void freestyle_vaultOverlay_revokeSoftFail_inConsole(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        HttpServer vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vault.createContext("/v1/auth/approle/login", exchange ->
                respond(exchange, 200, "{\"auth\":{\"client_token\":\"hvs.test-token\"}}"));
        vault.createContext("/v1/auth/token/lookup-self", exchange ->
                respond(exchange, 200, "{\"data\":{\"display_name\":\"approle\"}}"));
        vault.createContext("/v1/secret/data", exchange -> respond(
                exchange,
                200,
                "{\"data\":{\"data\":{\"IMAGE_TAG\":\"from-vault\"},\"metadata\":{\"version\":1}}}"));
        vault.createContext("/v1/auth/token/revoke-self", exchange ->
                respond(exchange, 403, "{\"errors\":[\"permission denied\"]}"));
        vault.start();
        String vaultBase = "http://127.0.0.1:" + vault.getAddress().getPort();

        try {
            SystemCredentialsProvider.getInstance().getCredentials().add(
                    new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL,
                            "vault-approle-revoke",
                            "Vault AppRole",
                            "role-id-SHOULD-NOT-LOG",
                            "secret-id-SHOULD-NOT-LOG"));
            SystemCredentialsProvider.getInstance().save();

            FreeStyleProject project = jenkins.createFreeStyleProject();
            PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
            step.setVault(vaultManual(vaultBase, "vault-approle-revoke", "myapp/prod"));
            step.setVerboseLogging(true);
            project.getBuildersList().add(step);

            FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
            jenkins.assertLogContains("[WARN] Vault revoke-self failed (token left for Vault TTL)", build);
            jenkins.assertLogContains("soft-fail: HTTP 403", build);
            jenkins.assertLogNotContains("hvs.test-token", build);
            assertTrue(createCalled.get());
            assertTrue(lastPath.get().contains("/standalone/repository"));
        } finally {
            vault.stop(0);
        }
    }

    @Test
    public void freestyle_portainerManual_deploysWithoutSystem(JenkinsRule jenkins) throws Exception {
        stacksEmpty.set(true);
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "step-portainer-key",
                        "Step Portainer token",
                        Secret.fromString("token-value")));
        SystemCredentialsProvider.getInstance().save();

        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("unused");
        cfg.setPortainerUrl("");
        cfg.setCredentialsId("");
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setPortainerConnectionMode(PortainerStackBuilder.MODE_MANUAL);
        step.setPortainerUrl(base);
        step.setPortainerCredentialsId("step-portainer-key");
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(createCalled.get());
        assertTrue(lastPath.get().contains("/standalone/repository"));
    }

    @Test
    public void freestyle_vaultInherit_withoutSystemConfig_failsClearly(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVault(vaultInherit("myapp/prod"));
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        // API stubs present; Global Vault System empty → unconfigured (not "plugin missing").
        jenkins.assertLogContains("Vault Plugin System is not configured", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_vaultManualPartial_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        VaultManual partial = new VaultManual("https://vault.example:8200", null);
        partial.setVaultPath("myapp/prod");
        step.setVault(partial);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Vault Manual requires", build);
        jenkins.assertLogContains("vaultAppRoleCredentialsId", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_vaultPartialConfig_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer("prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVault(new VaultManual("https://vault.example:8200", null));
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("partially configured", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void connectionSummary_mentionsSystem(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        assertEquals("System Portainer is not configured.", d.getPortainerConnectionSummary());
        assertFalse(d.getPortainerConnectionSummary().contains("Manage Jenkins"));
        configurePortainer("Production Portainer");
        assertEquals("System Portainer is configured.", d.getPortainerConnectionSummary());
        String vaultSummary = d.getVaultInheritSummary();
        assertEquals("Vault Plugin is not configured.", vaultSummary);
        assertEquals(vaultSummary, VaultPluginInherit.inheritSummary());
        assertFalse(d.isVaultInheritReady());
        assertEquals("Vault disabled.", VaultNone.SUMMARY);
        assertTrue(d.getVaultDescriptors().stream().anyMatch(x -> x instanceof VaultNone.DescriptorImpl));
    }

    private void configurePortainer(String name) throws IOException {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "portainer-api-key",
                        "Portainer token",
                        Secret.fromString("token-value")));
        SystemCredentialsProvider.getInstance().save();

        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName(name);
        cfg.setPortainerUrl(base);
        cfg.setCredentialsId("portainer-api-key");
        cfg.setConnectTimeoutMs(2000);
        cfg.setReadTimeoutMs(2000);
        cfg.save();
    }

    private static PortainerStackBuilder repoStack(
            String endpointId, String stackType, String stackName, String repositoryUrl) {
        PortainerStackBuilder step = new PortainerStackBuilder(endpointId, stackType, stackName);
        step.setRepositoryUrl(repositoryUrl);
        return step;
    }

    private static VaultInherit vaultInherit(String path) {
        VaultInherit inherit = new VaultInherit();
        inherit.setVaultPath(path);
        inherit.setVaultMount("secret");
        return inherit;
    }

    private static VaultManual vaultManual(String url, String credentialsId, String path) {
        VaultManual manual = new VaultManual(url, credentialsId);
        manual.setVaultPath(path);
        manual.setVaultMount("secret");
        return manual;
    }

    private static JSONObject vaultJson(
            Class<? extends VaultConnection> type,
            String url,
            String credentialsId,
            String path,
            String mount) {
        JSONObject vault = new JSONObject();
        vault.put("stapler-class", type.getName());
        vault.put("$class", type.getName());
        if (url != null) {
            vault.put("vaultUrl", url);
        }
        if (credentialsId != null) {
            vault.put("vaultAppRoleCredentialsId", credentialsId);
        }
        if (path != null) {
            vault.put("vaultPath", path);
        }
        if (mount != null) {
            vault.put("vaultMount", mount);
        }
        return vault;
    }

    /** True when path/method is a stack create, git redeploy, or YAML PUT update. */
    private static boolean isStackMutateApi(String path, String method) {
        if (path == null) {
            return false;
        }
        if (path.contains("/create/") || path.contains("/git/redeploy")) {
            return true;
        }
        return path.matches(".*/api/stacks/\\d+$") && "PUT".equalsIgnoreCase(method);
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
