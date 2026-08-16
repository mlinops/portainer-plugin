package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerSwarmConfigBuilderTest {

    private static final byte[] APP_SETTINGS_BYTES = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    private static final String APP_SETTINGS_NAME =
            SwarmConfigNaming.configName("app-settings", APP_SETTINGS_BYTES);

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicBoolean createCalled = new AtomicBoolean(false);
    private final AtomicReference<String> configsListBody = new AtomicReference<>("[]");

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        createCalled.set(false);
        configsListBody.set("[]");
        GitRepositoryFiles.listTestOverride.set(null);
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
            if (path != null && path.matches("/api/endpoints/\\d+/docker/configs$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 200, configsListBody.get());
                return;
            }
            if (path != null && path.endsWith("/docker/configs/create")
                    && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                createCalled.set(true);
                respond(exchange, 200, "{\"ID\":\"cfg-new\"}");
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
        GitRepositoryFiles.listTestOverride.set(null);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void freestyle_createsMissingConfig(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("======== Portainer Stack Config ========", build);
        jenkins.assertLogContains("[INFO] Preflight check of Git", build);
        jenkins.assertLogContains("[INFO] Git path=configs/swarm", build);
        jenkins.assertLogContains("[INFO] Configs found in Git - 1", build);
        jenkins.assertLogContains("[INFO] Preflight check of endpoint 1 (swarm)", build);
        jenkins.assertLogNotContains("starting portainer swarm config step", build);
        jenkins.assertLogNotContains("config step=", build);
        jenkins.assertLogContains("(created) " + APP_SETTINGS_NAME, build);
        jenkins.assertLogContains(
                "[INFO] Summary files=1 created=1 skipped=0 duration=",
                build);
        jenkins.assertLogNotContains("validated=", build);
        jenkins.assertLogNotContains("completed successfully", build);
        jenkins.assertLogNotContains("new configuration files:", build);
        assertEquals(
                APP_SETTINGS_NAME,
                build.getEnvironment(TaskListener.NULL).get("APP_SETTINGS"));
        jenkins.assertLogContains("created=1", build);
        assertTrue(createCalled.get());
        assertTrue(lastBody.get().contains("\"Name\":\"" + APP_SETTINGS_NAME + "\""));
        assertTrue(lastBody.get().contains("jenkins.portainer.config/base"));
    }

    @Test
    public void freestyle_skipsExistingConfigName(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        configsListBody.set("[{\"ID\":\"cfg1\",\"Spec\":{\"Name\":\"" + APP_SETTINGS_NAME + "\",\"Labels\":{}}}]");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("(skipped) " + APP_SETTINGS_NAME, build);
        jenkins.assertLogContains("skipped=1", build);
        jenkins.assertLogNotContains("new configuration files", build);
        jenkins.assertLogNotContains("(created)", build);
        jenkins.assertLogNotContains("removing stale configs", build);
        assertEquals(
                APP_SETTINGS_NAME,
                build.getEnvironment(TaskListener.NULL).get("APP_SETTINGS"));
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Validate-only — skipping Docker config mutations", build);
        jenkins.assertLogNotContains("(would create) " + APP_SETTINGS_NAME, build);
        jenkins.assertLogNotContains("validated=", build);
        jenkins.assertLogContains("created=0", build);
        assertTrue(build.getEnvironment(TaskListener.NULL).get("APP_SETTINGS") == null);
        assertTrue(!createCalled.get());
        assertTrue(lastPath.get() == null || !lastPath.get().endsWith("/configs/create"));
        assertFalse("POST".equalsIgnoreCase(lastMethod.get())
                && lastPath.get() != null
                && lastPath.get().endsWith("/configs/create"));
    }

    @Test
    public void freestyle_emptyFolder_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        GitRepositoryFiles.listTestOverride.set(req -> List.of());
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("No config files matched", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void pipeline_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "swarm-config-validate");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  portainerStackConfig(\n"
                        + "    endpointId: '1',\n"
                        + "    repositoryUrl: 'https://gitlab.example/group/configs.git',\n"
                        + "    configPath: 'configs/swarm',\n"
                        + "    validateOnly: true\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping Docker config mutations", run);
        jenkins.assertLogNotContains("(would create) " + APP_SETTINGS_NAME, run);
        jenkins.assertLogNotContains("validated=", run);
        assertTrue(!createCalled.get());
        assertTrue(lastPath.get() == null || !lastPath.get().endsWith("/configs/create"));
    }

    @Test
    public void descriptor_displayName(JenkinsRule jenkins) {
        assertEquals(
                "Portainer Stack Config",
                jenkins.jenkins.getDescriptorByType(PortainerSwarmConfigBuilder.DescriptorImpl.class)
                        .getDisplayName());
        assertTrue(new PortainerSwarmConfigBuilder("1").requiresWorkspace());
    }

    @Test
    public void freestyle_pruneOld_removesStaleConfig(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        AtomicBoolean deleteCalled = new AtomicBoolean(false);
        server.removeContext("/");
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
            if (path != null && path.matches("/api/endpoints/\\d+/docker/configs$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(
                        exchange,
                        200,
                        "[{\"ID\":\"cfg-old\",\"Spec\":{\"Name\":\"app-settings-deadbeef\","
                                + "\"Labels\":{\"jenkins.portainer.config/base\":\"app-settings\","
                                + "\"jenkins.portainer.config/hash\":\"deadbeef\"}}}]");
                return;
            }
            if (path != null && path.endsWith("/docker/configs/create")
                    && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                createCalled.set(true);
                respond(exchange, 200, "{\"ID\":\"cfg-new\"}");
                return;
            }
            if (path != null && path.endsWith("/docker/configs/cfg-old")
                    && "DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                deleteCalled.set(true);
                respond(exchange, 200, "{}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        step.setPruneOld(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("(created) " + APP_SETTINGS_NAME, build);
        assertTrue(createCalled.get());
        assertTrue(deleteCalled.get());
    }

    @Test
    public void freestyle_duplicateBasename_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        GitRepositoryFiles.listTestOverride.set(req -> List.of(
                new SwarmConfigFile("app-settings.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
                new SwarmConfigFile("app_settings.json", "{\"b\":2}".getBytes(StandardCharsets.UTF_8))));
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Duplicate env key", build);
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_unsupportedNamingStrategy_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        step.setNamingStrategy("legacy");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Unsupported namingStrategy", build);
    }

    @Test
    public void freestyle_swarmPreflightFails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stubGitFiles();
        server.removeContext("/");
        server.createContext("/", exchange -> {
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
                respond(exchange, 500, "{\"message\":\"not a swarm\"}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Swarm preflight failed", build);
    }

    @Test
    public void freestyle_gitPathNotFound_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        GitRepositoryFiles.listTestOverride.set(req -> {
            throw new IOException("Config path not found: missing");
        });
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmConfigBuilder step = new PortainerSwarmConfigBuilder("1");
        step.setRepositoryUrl("https://gitlab.example/group/configs.git");
        step.setConfigPath("configs/swarm");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Config path not found", build);
    }

    @Test
    public void normalizeNamingStrategy_blankDefaults() {
        assertEquals(
                PortainerSwarmConfigBuilder.NAMING_CONTENT_HASH,
                PortainerSwarmConfigBuilder.normalizeNamingStrategy(null));
        assertEquals(
                PortainerSwarmConfigBuilder.NAMING_CONTENT_HASH,
                PortainerSwarmConfigBuilder.normalizeNamingStrategy("  "));
        assertEquals("legacy", PortainerSwarmConfigBuilder.normalizeNamingStrategy(" legacy "));
    }

    @Test
    public void formValidation_namingStrategy(JenkinsRule jenkins) throws Exception {
        PortainerSwarmConfigBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerSwarmConfigBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        assertEquals(FormValidation.Kind.OK, d.doCheckNamingStrategy("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckNamingStrategy("contentHash", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckNamingStrategy("legacy", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckFileGlob("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckFileGlob("*.json", project).kind);
    }

    @Test
    public void descriptor_newInstance_nullFormData(JenkinsRule jenkins) {
        PortainerSwarmConfigBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerSwarmConfigBuilder.DescriptorImpl.class);
        // null form → empty JSONObject then Stapler bind (fails without required fields)
        assertThrows(Throwable.class, () -> d.newInstance((org.kohsuke.stapler.StaplerRequest2) null, null));
    }

    private static void stubGitFiles() {
        GitRepositoryFiles.listTestOverride.set(req -> List.of(
                new SwarmConfigFile("app-settings.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
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
