package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerSwarmSecretBuilderTest {

    private static final String SECRET_VALUE =
            "-----BEGIN PUBLIC KEY-----\nNEVER_LOG_THIS_SECRET_BODY\n-----END PUBLIC KEY-----\n";
    private static final byte[] SECRET_BYTES = SECRET_VALUE.getBytes(StandardCharsets.UTF_8);
    private static final String SECRET_NAME =
            SwarmConfigNaming.configName("rabbitmq_signing_key", SECRET_BYTES);

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicBoolean createCalled = new AtomicBoolean(false);
    private final AtomicReference<String> secretsListBody = new AtomicReference<>("[]");

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        createCalled.set(false);
        secretsListBody.set("[]");
        lastBody.set("");
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
                respond(exchange, 200, "{\"Id\":1,\"Name\":\"swarm\",\"Type\":1}");
                return;
            }
            if ("/api/endpoints".equals(path)) {
                respond(exchange, 200, "[{\"Id\":1,\"Name\":\"swarm\",\"Type\":1}]");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+/docker/swarm$")) {
                respond(exchange, 200, "{\"ID\":\"swarm-abc-123\"}");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+/docker/secrets$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 200, secretsListBody.get());
                return;
            }
            if (path != null && path.endsWith("/docker/secrets/create")
                    && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                createCalled.set(true);
                respond(exchange, 200, "{\"ID\":\"sec-new\"}");
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
        PortainerSwarmSecretBuilder.testVaultOverride = null;
        VaultConnections.testSkipPreflight = false;
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void freestyle_createsMissingSecret(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubVault();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        step.setVaultPath("applications/example/systems/rabbitmq");
        step.setSecretKeys("rabbitmq_signing_key");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("======== Portainer Stack Secret ========", build);
        jenkins.assertLogContains("[INFO] Preflight check of Vault", build);
        jenkins.assertLogContains("[INFO] Vault path=applications/example/systems/rabbitmq", build);
        jenkins.assertLogContains("[INFO] Keys configured in step - 1", build);
        jenkins.assertLogContains("[INFO] Keys found in Vault - 1", build);
        jenkins.assertLogContains("[INFO] Preflight check of endpoint 1 (swarm)", build);
        jenkins.assertLogContains("(created) " + SECRET_NAME, build);
        jenkins.assertLogContains(
                "[INFO] Summary files=1 created=1 skipped=0 duration=",
                build);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
        jenkins.assertLogNotContains("validated=", build);
        jenkins.assertLogNotContains("new secrets:", build);
        assertEquals(
                SECRET_NAME,
                build.getEnvironment(TaskListener.NULL).get("RABBITMQ_SIGNING_KEY"));
        assertTrue(createCalled.get());
        assertTrue(lastBody.get().contains("\"Name\":\"" + SECRET_NAME + "\""));
        assertTrue(lastBody.get().contains("jenkins.portainer.secret/base"));
        assertTrue(!lastBody.get().contains("NEVER_LOG_THIS_SECRET_BODY"));
    }

    @Test
    public void freestyle_skipsExistingSecretName(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubVault();
        secretsListBody.set("[{\"ID\":\"sec1\",\"Spec\":{\"Name\":\"" + SECRET_NAME + "\",\"Labels\":{}}}]");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        step.setVaultPath("applications/example/systems/rabbitmq");
        step.setSecretKeys("rabbitmq_signing_key");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("(skipped) " + SECRET_NAME, build);
        jenkins.assertLogContains("skipped=1", build);
        jenkins.assertLogNotContains("(created)", build);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
        assertEquals(
                SECRET_NAME,
                build.getEnvironment(TaskListener.NULL).get("RABBITMQ_SIGNING_KEY"));
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubVault();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        step.setVaultPath("applications/example/systems/rabbitmq");
        step.setSecretKeys("rabbitmq_signing_key");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Validate-only — skipping Docker secret mutations", build);
        jenkins.assertLogNotContains("(would create) " + SECRET_NAME, build);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
        jenkins.assertLogContains("created=0", build);
        assertTrue(build.getEnvironment(TaskListener.NULL).get("RABBITMQ_SIGNING_KEY") == null);
        assertTrue(!createCalled.get());
        assertTrue(lastPath.get() == null || !lastPath.get().endsWith("/secrets/create"));
        assertFalse("POST".equalsIgnoreCase(lastMethod.get())
                && lastPath.get() != null
                && lastPath.get().endsWith("/secrets/create"));
    }

    @Test
    public void freestyle_missingVaultKey_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        PortainerSwarmSecretBuilder.testVaultOverride = Map.of("other_key", "x");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        step.setVaultPath("applications/example/systems/rabbitmq");
        step.setSecretKeys("rabbitmq_signing_key");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Keys differ: missing=1 extra=1", build);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void pipeline_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubVault();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "swarm-secret-validate");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  portainerStackSecret(\n"
                        + "    endpointId: '1',\n"
                        + "    vaultPath: 'applications/example/systems/rabbitmq',\n"
                        + "    secretKeys: 'rabbitmq_signing_key',\n"
                        + "    validateOnly: true\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping Docker secret mutations", run);
        jenkins.assertLogNotContains("(would create) " + SECRET_NAME, run);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", run);
        assertTrue(!createCalled.get());
        assertTrue(lastPath.get() == null || !lastPath.get().endsWith("/secrets/create"));
    }

    @Test
    public void pipeline_withoutNode_validateOnly(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubVault();
        assertFalse(new PortainerSwarmSecretBuilder("1").requiresWorkspace());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "swarm-secret-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "portainerStackSecret(\n"
                        + "  endpointId: '1',\n"
                        + "  vaultPath: 'applications/example/systems/rabbitmq',\n"
                        + "  secretKeys: 'rabbitmq_signing_key',\n"
                        + "  validateOnly: true\n"
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping Docker secret mutations", run);
        jenkins.assertLogNotContains("(would create) " + SECRET_NAME, run);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", run);
        assertTrue(!createCalled.get());
        assertTrue(lastPath.get() == null || !lastPath.get().endsWith("/secrets/create"));
    }

    @Test
    public void descriptor_displayName(JenkinsRule jenkins) {
        assertEquals(
                "Portainer Stack Secret",
                jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class)
                        .getDisplayName());
    }

    @Test
    public void descriptor_newInstance_nullFormData_skipsFlatten(JenkinsRule jenkins) {
        PortainerSwarmSecretBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
        // false branch of formData != null (skip flatten) then super bind fails
        assertThrows(Throwable.class, () -> d.newInstance((org.kohsuke.stapler.StaplerRequest2) null, null));
    }

    @Test
    public void descriptor_vaultInheritHelpers(JenkinsRule jenkins) {
        PortainerSwarmSecretBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
        // Test doubles make the Vault API loadable; Global System remains empty.
        assertTrue(d.isVaultPluginPresent());
        assertFalse(d.isVaultInheritReady());
        assertEquals("Vault Plugin is not configured.", d.getVaultInheritSummary());
    }

    private static void stubVault() {
        PortainerSwarmSecretBuilder.testVaultOverride = Map.of("rabbitmq_signing_key", SECRET_VALUE);
    }

    private void configurePortainer(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "portainer-api-key",
                        "portainer",
                        Secret.fromString("test-token")));
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("lab");
        cfg.setPortainerUrl(base);
        cfg.setCredentialsId("portainer-api-key");
        cfg.save();
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
