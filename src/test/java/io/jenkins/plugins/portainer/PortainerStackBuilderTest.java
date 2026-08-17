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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        configurePortainer(jenkins, "prod");
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
        jenkins.assertLogContains("======== Portainer Stack Deployment ========", build);
        jenkins.assertLogContains("[INFO] Stack name=demo type=compose", build);
        jenkins.assertLogNotContains("starting stack step", build);
        jenkins.assertLogNotContains("[INFO] config ", build);
        jenkins.assertLogNotContains("compose soft-check", build);
        jenkins.assertLogContains("[INFO] Preflight check of endpoint", build);
        jenkins.assertLogContains("[INFO] Env keys=1", build);
        jenkins.assertLogNotContains("[INFO] Stack created", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created stackId=11 duration=", build);
        jenkins.assertLogNotContains("completed successfully", build);
        jenkins.assertLogNotContains("creating from Git", build);
        jenkins.assertLogNotContains("GET /api/", build);
        jenkins.assertLogNotContains("[DEBUG]", build);
        assertTrue(lastPath.get().contains("/standalone/repository"));
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
        assertTrue(loaded.isValidateOnly());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("[INFO] Preflight check of endpoint", build);
        jenkins.assertLogContains("[INFO] Validate-only — skipping deploy", build);
        jenkins.assertLogNotContains("Would create-or-redeploy stack from Git", build);
        jenkins.assertLogContains("[INFO] Summary outcome=validated", build);
        jenkins.assertLogNotContains("completed successfully", build);
        jenkins.assertLogNotContains("creating from Git", build);
        jenkins.assertLogNotContains("stack created", build);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
        // validateOnly looks up the stack after preflight, so last path is /api/stacks
        assertTrue(lastPath.get() != null
                && (lastPath.get().equals("/api/stacks")
                        || lastPath.get().matches(".*/api/endpoints/\\d+$")));
    }

    @Test
    public void pipeline_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "stack-validate-only");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  portainerStack(\n"
                        + "    endpointId: '1',\n"
                        + "    stackType: 'compose',\n"
                        + "    stackName: 'demo',\n"
                        + "    repositoryUrl: 'https://gitlab.example/group/stack.git',\n"
                        + "    validateOnly: true\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping deploy", run);
        jenkins.assertLogContains("outcome=validated", run);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void pipeline_withoutNode_validateOnly(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);
        assertFalse(new PortainerStackBuilder("1", "compose", "demo").requiresWorkspace());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "stack-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "portainerStack(\n"
                        + "  endpointId: '1',\n"
                        + "  stackType: 'compose',\n"
                        + "  stackName: 'demo',\n"
                        + "  repositoryUrl: 'https://gitlab.example/group/stack.git',\n"
                        + "  validateOnly: true\n"
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping deploy", run);
        jenkins.assertLogContains("outcome=validated", run);
        assertTrue(!createCalled.get());
        assertFalse(isStackMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void freestyle_validateOnly_yamlInvalid_failsWithoutMutate(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
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
     * PORT-18: Freestyle Save posts f:radioBlock as nested JSON {@code {"value":"…"}}.
     * Descriptor.newInstance must flatten before Stapler bind (Object setters do not work).
     */
    @Test
    public void stapler_radioBlockJson_bindsInheritAndManualFields(JenkinsRule jenkins) throws Exception {
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
            json.put("vaultPath", "basic");
            json.put("vaultMount", "applications");
            json.put("vaultNamespace", "");
            json.put("vaultVersion", "");
            json.put("prune", false);
            json.put("repullImageAndRedeploy", true);

            // Nested radioBlock shape from Freestyle configSubmit (lab reproduction).
            JSONObject portainerMode = new JSONObject();
            portainerMode.put("value", "inherit");
            json.put("portainerConnectionMode", portainerMode);

            JSONObject vaultMode = new JSONObject();
            vaultMode.put("value", "manual");
            vaultMode.put("vaultUrl", "https://vault.example:8200");
            vaultMode.put("vaultAppRoleCredentialsId", "jenkins-portainer-simple");
            json.put("vaultConnectionMode", vaultMode);

            // Guard: without flatten, Stapler would try to instantiate Object/String from JSONObject.
            assertTrue(json.get("portainerConnectionMode") instanceof JSONObject);
            assertTrue(json.get("vaultConnectionMode") instanceof JSONObject);

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            PortainerStackBuilder bound =
                    (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);

            // Flatten mutates form JSON in place before bind.
            assertEquals(PortainerStackBuilder.MODE_INHERIT, json.getString("portainerConnectionMode"));
            assertEquals(PortainerStackBuilder.MODE_MANUAL, json.getString("vaultConnectionMode"));
            assertEquals("https://vault.example:8200", json.getString("vaultUrl"));
            return bound;
        });

        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
        assertEquals(PortainerStackBuilder.MODE_MANUAL, step.getVaultConnectionMode());
        assertEquals("https://vault.example:8200", step.getVaultUrl());
        assertEquals("jenkins-portainer-simple", step.getVaultAppRoleCredentialsId());
        assertEquals("245", step.getEndpointId());
        assertEquals("test-nginx", step.getStackName());
        assertEquals("basic", step.getVaultPath());
        assertEquals("applications", step.getVaultMount());
    }

    @Test
    public void stapler_radioBlockJson_bindsPortainerManual(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("endpointId", "1");
            json.put("stackType", "compose");
            json.put("stackName", "demo");
            json.put("repositoryUrl", "https://gitlab.example/group/stack.git");

            JSONObject portainerMode = new JSONObject();
            portainerMode.put("value", "manual");
            portainerMode.put("portainerUrl", "https://portainer.example:9443");
            portainerMode.put("portainerCredentialsId", "portainer-api-key");
            json.put("portainerConnectionMode", portainerMode);

            JSONObject vaultMode = new JSONObject();
            vaultMode.put("value", "inherit");
            json.put("vaultConnectionMode", vaultMode);

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertEquals(PortainerStackBuilder.MODE_MANUAL, step.getPortainerConnectionMode());
        assertEquals("https://portainer.example:9443", step.getPortainerUrl());
        assertEquals("portainer-api-key", step.getPortainerCredentialsId());
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getVaultConnectionMode());
    }

    @Test
    public void stapler_radioBlockJson_bindsVaultNone(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("endpointId", "1");
            json.put("stackType", "compose");
            json.put("stackName", "demo");
            json.put("repositoryUrl", "https://gitlab.example/group/stack.git");

            JSONObject portainerMode = new JSONObject();
            portainerMode.put("value", "inherit");
            json.put("portainerConnectionMode", portainerMode);

            JSONObject vaultMode = new JSONObject();
            vaultMode.put("value", "none");
            json.put("vaultConnectionMode", vaultMode);

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertEquals(PortainerStackBuilder.MODE_NONE, step.getVaultConnectionMode());
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
    }

    @Test
    public void freestyle_vaultNone_skipsVaultAndDeploys(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_NONE);
        step.setVaultPath("myapp/prod");
        step.setVaultUrl("https://vault.example:8200");
        step.setEnv("IMAGE_TAG=1");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
        assertEquals(PortainerStackBuilder.MODE_NONE, loaded.getVaultConnectionMode());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Env keys=", build);
        jenkins.assertLogNotContains("[INFO] Stack created", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created", build);
        jenkins.assertLogNotContains("Vault Manual reading", build);
        jenkins.assertLogNotContains("HashiCorp Vault Plugin", build);
        jenkins.assertLogNotContains("Preflight check of Vault", build);
    }

    @Test
    public void freestyle_redeploysWhenStackExists(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(false);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setPrune(true);
        step.setRepullImageAndRedeploy(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("[INFO] Stack redeployed", build);
        jenkins.assertLogContains("[INFO] Summary outcome=updated stackId=11 duration=", build);
        jenkins.assertLogNotContains("stack exists", build);
        jenkins.assertLogNotContains("completed successfully", build);
        assertTrue(lastPath.get().contains("/git/redeploy"));
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_yamlCreate_whenStackMissing_noYamlBodyInLog(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
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
        jenkins.assertLogNotContains("[INFO] Stack created", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created", build);
        jenkins.assertLogNotContains("creating from YAML", build);
        jenkins.assertLogNotContains(secretMarker, build);
        jenkins.assertLogNotContains("TOKEN=", build);
        assertTrue(lastPath.get().contains("/standalone/string"));
        assertTrue(lastBody.get().contains("StackFileContent"));
        assertTrue(lastBody.get().contains(secretMarker)); // request body OK; must not appear in console
    }

    @Test
    public void freestyle_yamlUpdate_whenStackExists(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(false);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        step.setStackFileContent("services:\n  web:\n    image: nginx:alpine\n");
        step.setPrune(true);
        step.setRepullImageAndRedeploy(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("[INFO] Stack updated", build);
        jenkins.assertLogNotContains("[INFO] Stack created", build);
        jenkins.assertLogContains("[INFO] Summary outcome=updated stackId=11 duration=", build);
        jenkins.assertLogNotContains("stack exists", build);
        assertTrue(lastPath.get().matches(".*/api/stacks/11$") || lastPath.get().endsWith("/api/stacks/11"));
        assertTrue(!lastPath.get().contains("git/redeploy"));
        assertEquals("PUT", lastMethod.get());
        assertTrue(createCalled.get());
        assertTrue(lastBody.get().contains("\"Prune\":true") || lastBody.get().contains("\"Prune\": true"));
    }

    @Test
    public void freestyle_yamlMissingContent_failsBeforePortainer(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        lastPath.set(null);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Stack YAML content is required", build);
        assertTrue(lastPath.get() == null);
    }

    @Test
    public void freestyle_invalidInlineYaml_failsBeforePortainer(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        lastPath.set(null);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = new PortainerStackBuilder("1", "compose", "demo");
        step.setStackSource(PortainerStackBuilder.SOURCE_YAML);
        step.setStackFileContent("services: [\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Invalid Compose YAML", build);
        assertTrue(lastPath.get() == null);
    }

    @Test
    public void stapler_radioBlockJson_bindsStackSourceYaml(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = new JSONObject();
            json.put("endpointId", "1");
            json.put("stackType", "compose");
            json.put("stackName", "demo");

            JSONObject portainerMode = new JSONObject();
            portainerMode.put("value", "inherit");
            json.put("portainerConnectionMode", portainerMode);

            JSONObject vaultMode = new JSONObject();
            vaultMode.put("value", "inherit");
            json.put("vaultConnectionMode", vaultMode);

            JSONObject source = new JSONObject();
            source.put("value", "yaml");
            source.put("stackFileContent", "services:\n  web:\n    image: nginx:alpine\n");
            json.put("stackSource", source);

            PortainerStackBuilder.DescriptorImpl d =
                    jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
            return (PortainerStackBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });

        assertEquals(PortainerStackBuilder.SOURCE_YAML, step.getStackSource());
        assertTrue(step.getStackFileContent() != null && step.getStackFileContent().contains("nginx"));
    }

    @Test
    public void pipeline_yamlCreate_smoke(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "default");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "yaml-create");
        String script = ""
                + "node {\n"
                + "  portainerStack(\n"
                + "    endpointId: '1',\n"
                + "    stackType: 'compose',\n"
                + "    stackName: 'demo',\n"
                + "    stackSource: 'yaml',\n"
                + "    stackFileContent: '''\n"
                + "services:\n"
                + "  web:\n"
                + "    image: nginx:alpine\n"
                + "'''\n"
                + "  )\n"
                + "}\n";
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogNotContains("[INFO] Stack created", run);
        jenkins.assertLogContains("[INFO] Summary outcome=created", run);
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
        assertTrue(lastPath.get() == null);
    }

    @Test
    public void pipeline_autoDeployWhenMissing_createsStack(JenkinsRule jenkins)
            throws Exception {
        configurePortainer(jenkins, "Production Portainer");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "deploy-missing");
        String script = ""
                + "node {\n"
                + "  portainerStack(\n"
                + "    endpointId: '1',\n"
                + "    stackType: 'compose',\n"
                + "    stackName: 'demo',\n"
                + "    repositoryUrl: 'https://gitlab.example/group/stack.git',\n"
                + "    composeFilePath: 'docker-compose.yml'\n"
                + "  )\n"
                + "}\n";
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogNotContains("[INFO] Stack created", run);
        jenkins.assertLogContains("[INFO] Summary outcome=created", run);
        jenkins.assertLogNotContains("completed successfully", run);
        assertTrue(lastPath.get().contains("/standalone/repository"));
    }

    @Test
    public void pipeline_rejectsLegacyActionAndInstanceIdParams(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "Production Portainer");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "deploy-legacy-params");
        String script = ""
                + "node {\n"
                + "  portainerStack(\n"
                + "    action: 'redeploy',\n"
                + "    instanceId: 'ignored',\n"
                + "    endpointId: '1',\n"
                + "    stackType: 'compose',\n"
                + "    stackName: 'demo',\n"
                + "    repositoryUrl: 'https://gitlab.example/group/stack.git',\n"
                + "    composeFilePath: 'docker-compose.yml'\n"
                + "  )\n"
                + "}\n";
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("action", run);
        jenkins.assertLogContains("instanceId", run);
        assertFalse(
                isStackMutateApi(lastPath.get(), lastMethod.get()),
                "legacy params must not reach stack mutate APIs; lastPath=" + lastPath.get());
    }

    @Test
    public void pipeline_autoRedeploy_smoke(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "default");
        stacksEmpty.set(false);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "p");
        String script = ""
                + "node {\n"
                + "  portainerStack(\n"
                + "    endpointId: '1',\n"
                + "    stackType: 'compose',\n"
                + "    stackName: 'demo',\n"
                + "    repositoryUrl: 'https://gitlab.example/group/stack.git',\n"
                + "    composeFilePath: 'docker-compose.yml',\n"
                + "    prune: true,\n"
                + "    repullImageAndRedeploy: true\n"
                + "  )\n"
                + "}\n";
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogNotContains("[INFO] Stack redeployed", run);
        jenkins.assertLogContains("[INFO] Summary outcome=updated", run);
        assertTrue(lastPath.get().contains("/git/redeploy"));
    }

    @Test
    public void pipeline_verboseLogging_includesHttpPaths(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "default");
        stacksEmpty.set(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "verbose-deploy");
        String script = ""
                + "node {\n"
                + "  portainerStack(\n"
                + "    endpointId: '1',\n"
                + "    stackType: 'compose',\n"
                + "    stackName: 'demo',\n"
                + "    repositoryUrl: 'https://gitlab.example/group/stack.git',\n"
                + "    composeFilePath: 'docker-compose.yml',\n"
                + "    verboseLogging: true\n"
                + "  )\n"
                + "}\n";
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("[INFO] Stack name=demo type=compose", run);
        jenkins.assertLogContains("[DEBUG] GET /api/", run);
        jenkins.assertLogContains("[DEBUG] Connection=", run);
        jenkins.assertLogNotContains("[INFO] Stack created", run);
        jenkins.assertLogContains("[INFO] Summary outcome=created", run);
        jenkins.assertLogNotContains("[WARNING]", run);
    }

    @Test
    public void formValidation_stackNameAndComposePath(JenkinsRule jenkins) throws Exception {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        FormValidation stackOk = d.doCheckStackName("my-app", project);
        assertEquals(FormValidation.Kind.OK, stackOk.kind);
        FormValidation stackBad = d.doCheckStackName("MyApp", project);
        assertEquals(FormValidation.Kind.ERROR, stackBad.kind);
        assertTrue(Character.isUpperCase(stackBad.getMessage().charAt(0)));
        assertTrue(stackBad.getMessage().endsWith("."));
        FormValidation stackEmpty = d.doCheckStackName("", project);
        assertEquals(FormValidation.Kind.ERROR, stackEmpty.kind);
        assertEquals("Stack name is required.", stackEmpty.getMessage());

        assertEquals(FormValidation.Kind.OK, d.doCheckComposeFilePath("docker-compose.yml", "repository", project).kind);
        FormValidation composeEmpty = d.doCheckComposeFilePath("", "repository", project);
        assertEquals(FormValidation.Kind.ERROR, composeEmpty.kind);
        assertEquals("Compose file path is required.", composeEmpty.getMessage());
        FormValidation composeTraversal = d.doCheckComposeFilePath("../secret.yml", "repository", project);
        assertEquals(FormValidation.Kind.ERROR, composeTraversal.kind);
        assertTrue(composeTraversal.getMessage().endsWith("."));
        assertEquals(FormValidation.Kind.ERROR, d.doCheckComposeFilePath("/abs/compose.yml", "repository", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckComposeFilePath("", "yaml", project).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckStackFileContent("services:\n  web:\n    image: nginx:alpine\n", "yaml", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStackFileContent("", "yaml", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStackFileContent("", "repository", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckRepositoryUrl("", "yaml", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckRepositoryUrl("", "repository", project).kind);

        assertEquals(FormValidation.Kind.OK, d.doCheckEnv("IMAGE_TAG=1.2.3\n# comment\n", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckEnv("", project).kind);
        // Bare KEY is shorthand for KEY=${KEY}
        assertEquals(FormValidation.Kind.OK, d.doCheckEnv("NOEQUALS", project).kind);
        FormValidation envBadKey = d.doCheckEnv("bad-key=1", project);
        assertEquals(FormValidation.Kind.ERROR, envBadKey.kind);

        assertEquals(FormValidation.Kind.OK, d.doCheckStackType("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStackType("compose", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStackType("k8s", project).kind);

        assertTrue(d.getPortainerConnectionSummary().contains("System Portainer is not configured"));
        assertFalse(d.getPortainerConnectionSummary().contains("Manage Jenkins"));
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
        configurePortainer(jenkins, "prod");
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
        configurePortainer(jenkins, "prod");
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
        jenkins.assertLogContains("======== Portainer Stack Deployment ========", build);
        jenkins.assertLogContains("[ERROR] Preflight failed: HTTP 500 - boom", build);
        jenkins.assertLogNotContains("ERROR: Portainer:", build);
        jenkins.assertLogNotContains("at io.jenkins.plugins.portainer.PortainerClient", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_preflight_unreachable_failsBeforeCreate(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
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
        configurePortainer(jenkins, "prod");
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
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);
        lastPath.set(null);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FilePath workspace = jenkins.jenkins.getWorkspaceFor(project);
        workspace.mkdirs();
        workspace.child("docker-compose.yml").write("services: [\n", "UTF-8");
        project.getBuildersList().add(repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("Invalid Compose YAML", build);
        jenkins.assertLogNotContains("[INFO] Stack created", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created", build);
        assertTrue(lastPath.get() != null && lastPath.get().contains("/standalone/repository"));
    }

    @Test
    public void freestyle_vaultOverlay_usernamePassword_noSecretsInLog(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
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
            // Explicit Manual (also migrated when vaultUrl+cred set without mode).
            step.setVaultConnectionMode(PortainerStackBuilder.MODE_MANUAL);
            step.setVaultUrl(vaultBase);
            step.setVaultAppRoleCredentialsId("vault-approle");
            step.setVaultPath("myapp/prod");
            step.setVaultMount("secret");
            project.getBuildersList().add(step);

            jenkins.configRoundtrip(project);
            PortainerStackBuilder loaded = project.getBuildersList().get(PortainerStackBuilder.class);
            assertEquals("vault-approle", loaded.getVaultAppRoleCredentialsId());
            assertEquals(vaultBase, loaded.getVaultUrl());
            assertEquals("myapp/prod", loaded.getVaultPath());
            assertEquals(PortainerStackBuilder.MODE_MANUAL, loaded.getVaultConnectionMode());

            FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
            jenkins.assertLogContains("Preflight check of Vault", build);
            jenkins.assertLogContains(
                    "Env keys=3", build);
            jenkins.assertLogNotContains("[INFO] Stack created", build);
            jenkins.assertLogContains("[INFO] Summary outcome=created", build);
            jenkins.assertLogNotContains("completed successfully", build);
            jenkins.assertLogNotContains("role-id-SHOULD-NOT-LOG", build);
            jenkins.assertLogNotContains("secret-id-SHOULD-NOT-LOG", build);
            jenkins.assertLogNotContains("s3cret", build);
            jenkins.assertLogNotContains("hvs.test-token", build);
            jenkins.assertLogNotContains("[DEBUG]", build);
            assertTrue(vaultLoginBody.get() != null && vaultLoginBody.get().contains("role_id"));
        } finally {
            vault.stop(0);
        }
    }

    @Test
    public void freestyle_vaultOverlay_revokeSoftFail_inConsole(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
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
            step.setVaultConnectionMode(PortainerStackBuilder.MODE_MANUAL);
            step.setVaultUrl(vaultBase);
            step.setVaultAppRoleCredentialsId("vault-approle-revoke");
            step.setVaultPath("myapp/prod");
            step.setVaultMount("secret");
            step.setVerboseLogging(true);
            project.getBuildersList().add(step);

            FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
            jenkins.assertLogContains("[WARN] Vault revoke-self failed (token left for Vault TTL)", build);
            jenkins.assertLogContains("soft-fail: HTTP 403", build);
            jenkins.assertLogNotContains("[INFO] Stack created", build);
            jenkins.assertLogContains("[INFO] Summary outcome=created", build);
            jenkins.assertLogNotContains("hvs.test-token", build);
            jenkins.assertLogNotContains("[WARNING]", build);
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

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("[INFO] Stack name=demo type=compose", build);
        jenkins.assertLogNotContains("[INFO] Stack created", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created", build);
    }

    @Test
    public void freestyle_vaultInherit_withoutSystemConfig_failsClearly(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_INHERIT);
        step.setVaultPath("myapp/prod");
        step.setVaultMount("secret");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        // API stubs present; Global Vault System empty → unconfigured (not "plugin missing").
        jenkins.assertLogContains("Vault Plugin System is not configured", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_vaultManualPartial_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_MANUAL);
        step.setVaultUrl("https://vault.example:8200");
        step.setVaultPath("myapp/prod");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Vault Manual requires", build);
        jenkins.assertLogContains("vaultAppRoleCredentialsId", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_vaultPartialConfig_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins, "prod");
        stacksEmpty.set(true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        // Legacy-style: URL without path → Manual migration + partial fail (path required).
        step.setVaultUrl("https://vault.example:8200");
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
        configurePortainer(jenkins, "Production Portainer");
        assertEquals("System Portainer is configured.", d.getPortainerConnectionSummary());
        // API test doubles present; Global Vault System remains empty → not configured.
        String vaultSummary = d.getVaultInheritSummary();
        assertEquals("Vault Plugin is not configured.", vaultSummary);
        assertEquals(vaultSummary, VaultPluginInherit.inheritSummary());
        assertFalse(d.isVaultInheritReady());
        assertEquals("Vault disabled.", PortainerStackBuilder.DescriptorImpl.VAULT_NONE_SUMMARY);
    }

    @Test
    public void descriptor_newInstance_nullFormData(JenkinsRule jenkins) {
        PortainerStackBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerStackBuilder.DescriptorImpl.class);
        // null form → empty JSONObject then Stapler bind (fails without required fields)
        assertThrows(Throwable.class, () -> d.newInstance((org.kohsuke.stapler.StaplerRequest2) null, null));
    }

    private void configurePortainer(JenkinsRule jenkins, String name) throws Exception {
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
