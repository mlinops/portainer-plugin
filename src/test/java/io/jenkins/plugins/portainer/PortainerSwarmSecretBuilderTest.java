package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
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
public class PortainerSwarmSecretBuilderTest {

    private static final String SECRET_VALUE =
            "-----BEGIN PUBLIC KEY-----\nNEVER_LOG_THIS_SECRET_BODY\n-----END PUBLIC KEY-----\n";
    private static final byte[] SECRET_BYTES = SECRET_VALUE.getBytes(StandardCharsets.UTF_8);
    private static final String SECRET_NAME =
            SwarmConfigNaming.configName("rabbitmq_signing_key", SECRET_BYTES);

    private HttpServer server;
    private HttpServer vaultServer;
    private String base;
    private String vaultBase;
    private final AtomicReference<String> vaultKvBody = new AtomicReference<>();
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
        vaultKvBody.set(vaultKvJson("rabbitmq_signing_key", SECRET_VALUE));
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
        startVaultServer();
    }

    @AfterEach
    public void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (vaultServer != null) {
            vaultServer.stop(0);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void freestyle_createsMissingSecret(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        applyManualVault(step);
        step.setSecretKeys("rabbitmq_signing_key");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
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
        secretsListBody.set("[{\"ID\":\"sec1\",\"Spec\":{\"Name\":\"" + SECRET_NAME + "\",\"Labels\":{}}}]");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        applyManualVault(step);
        step.setSecretKeys("rabbitmq_signing_key");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
        assertEquals(
                SECRET_NAME,
                build.getEnvironment(TaskListener.NULL).get("RABBITMQ_SIGNING_KEY"));
        assertTrue(!createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        applyManualVault(step);
        step.setSecretKeys("rabbitmq_signing_key");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", build);
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
        vaultKvBody.set(vaultKvJson("other_key", "x"));
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        applyManualVault(step);
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
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "swarm-secret-validate");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  portainerStackSecret(\n"
                        + pipelineSecretArgs(true)
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogNotContains("NEVER_LOG_THIS_SECRET_BODY", run);
        assertTrue(!createCalled.get());
        assertTrue(lastPath.get() == null || !lastPath.get().endsWith("/secrets/create"));
    }

    @Test
    public void pipeline_withoutNode_validateOnly(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        assertFalse(new PortainerSwarmSecretBuilder("1").requiresWorkspace());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "swarm-secret-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "portainerStackSecret(\n"
                        + pipelineSecretArgs(true)
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
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
    public void descriptor_vaultInheritHelpers(JenkinsRule jenkins) {
        PortainerSwarmSecretBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
        // Optional Vault plugin is on the test classpath; Global System remains empty.
        assertTrue(d.isVaultPluginPresent());
        assertFalse(d.isVaultInheritReady());
        assertEquals("Vault Plugin is not configured.", d.getVaultInheritSummary());
        assertFalse(d.getVaultDescriptors().stream().anyMatch(x -> x instanceof VaultNone.DescriptorImpl));
        assertTrue(d.isApplicable(FreeStyleProject.class));
        assertTrue(d.isApplicable(AbstractProject.class));
        assertNotNull(d.getPortainerConnectionSummary());
    }

    @Test
    public void vault_defaultsToInherit_andRejectsNone(JenkinsRule jenkins) {
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder(null);
        assertEquals("", step.getEndpointId());
        assertInstanceOf(VaultInherit.class, step.getVault());
        step.setVault(new VaultNone());
        assertInstanceOf(VaultInherit.class, step.getVault());
        step.setVault(null);
        assertInstanceOf(VaultInherit.class, step.getVault());
        step.setPortainerConnectionMode("none");
        assertEquals(PortainerSwarmSecretBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
        step.setSecretKeys(null);
        assertEquals("", step.getSecretKeys());
        step.setPortainerUrl(" https://portainer.example ");
        assertEquals("https://portainer.example", step.getPortainerUrl());
        step.setPortainerCredentialsId("  ");
        assertNull(step.getPortainerCredentialsId());
        step.setPortainerCredentialsId("portainer-api-key");
        assertEquals("portainer-api-key", step.getPortainerCredentialsId());
        step.setVerboseLogging(true);
        assertTrue(step.isVerboseLogging());
        step.setPruneOld(true);
        assertTrue(step.isPruneOld());
    }

    @Test
    public void stapler_bindsVaultInheritAndManual(JenkinsRule jenkins) throws Exception {
        PortainerSwarmSecretBuilder inherit = jenkins.executeOnServer(() -> {
            JSONObject json = secretJson(vaultJson(VaultInherit.class, null, null, "apps/demo", "secret"));
            PortainerSwarmSecretBuilder.DescriptorImpl d =
                    jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
            return (PortainerSwarmSecretBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });
        assertInstanceOf(VaultInherit.class, inherit.getVault());
        assertEquals("apps/demo", inherit.getVault().getVaultPath());
        assertEquals("secret", inherit.getVault().getVaultMount());

        PortainerSwarmSecretBuilder manual = jenkins.executeOnServer(() -> {
            JSONObject json = secretJson(vaultJson(
                    VaultManual.class,
                    "https://vault.example:8200",
                    "vault-approle",
                    "apps/demo",
                    "secret"));
            PortainerSwarmSecretBuilder.DescriptorImpl d =
                    jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
            return (PortainerSwarmSecretBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });
        assertInstanceOf(VaultManual.class, manual.getVault());
        assertEquals("https://vault.example:8200", manual.getVault().getVaultUrl());
        assertEquals("vault-approle", manual.getVault().getVaultAppRoleCredentialsId());
    }

    @Test
    public void stapler_bindsVaultNone_asInherit(JenkinsRule jenkins) throws Exception {
        PortainerSwarmSecretBuilder step = jenkins.executeOnServer(() -> {
            JSONObject json = secretJson(vaultJson(VaultNone.class, null, null, null, null));
            PortainerSwarmSecretBuilder.DescriptorImpl d =
                    jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
            return (PortainerSwarmSecretBuilder) d.newInstance(Stapler.getCurrentRequest2(), json);
        });
        assertInstanceOf(VaultInherit.class, step.getVault());
    }

    @Test
    public void xstream_legacyVaultModes(JenkinsRule jenkins) throws Exception {
        PortainerSwarmSecretBuilder none = loadBuilder(jenkins, "secret-legacy-vault-none.xml");
        assertInstanceOf(VaultInherit.class, none.getVault());
        assertEquals("apps/demo", none.getVault().getVaultPath());
        assertFalse(Items.XSTREAM2.toXML(none).contains("<vaultConnectionMode>"));

        PortainerSwarmSecretBuilder inherit = loadBuilder(jenkins, "secret-legacy-vault-inherit.xml");
        assertInstanceOf(VaultInherit.class, inherit.getVault());
        assertEquals("apps/demo", inherit.getVault().getVaultPath());
        assertEquals("secret", inherit.getVault().getVaultMount());

        PortainerSwarmSecretBuilder manual = loadBuilder(jenkins, "secret-legacy-vault-manual.xml");
        assertInstanceOf(VaultManual.class, manual.getVault());
        assertEquals("https://vault.example:8200", manual.getVault().getVaultUrl());
        assertEquals("vault-approle", manual.getVault().getVaultAppRoleCredentialsId());
        assertEquals("apps/demo", manual.getVault().getVaultPath());

        PortainerSwarmSecretBuilder nestedNone = loadBuilder(jenkins, "secret-nested-vault-none.xml");
        assertInstanceOf(VaultInherit.class, nestedNone.getVault());
    }

    @Test
    public void configRoundtrip_keepsInherit(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerSwarmSecretBuilder step = new PortainerSwarmSecretBuilder("1");
        VaultInherit inherit = new VaultInherit();
        inherit.setVaultPath("apps/demo");
        step.setVault(inherit);
        step.setSecretKeys("app_key");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        PortainerSwarmSecretBuilder loaded = project.getBuildersList().get(PortainerSwarmSecretBuilder.class);
        assertInstanceOf(VaultInherit.class, loaded.getVault());
        assertFalse(loaded.getVault() instanceof VaultNone);
    }

    @Test
    public void formValidation_secretKeysAndConnection(JenkinsRule jenkins) throws Exception {
        PortainerSwarmSecretBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(FormValidation.Kind.ERROR, d.doCheckSecretKeys("", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckSecretKeys("   ", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckSecretKeys("app_key", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckSecretKeys("APP_KEY=${APP_KEY}", project).kind);

        assertEquals(FormValidation.Kind.ERROR, d.doCheckEndpointId("1", "inherit", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckEndpointId("1", "manual", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckEndpointId("", "manual", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPortainerUrl("", "inherit", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckPortainerUrl("", "manual", project).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckPortainerUrl("https://portainer.example", "manual", project).kind);
        assertNotNull(d.doFillPortainerCredentialsIdItems(project, ""));
    }

    @Test
    public void doCheckSecretKeys_withoutConfigure_denied(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy()
                        .grant(Jenkins.READ, Item.READ).everywhere().to("viewer"));

        PortainerSwarmSecretBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(PortainerSwarmSecretBuilder.DescriptorImpl.class);
        try (ACLContext ignored = ACL.as(User.getById("viewer", true))) {
            assertThrows(AccessDeniedException.class, () -> d.doCheckSecretKeys("app_key", project));
        }
    }

    private void startVaultServer() throws IOException {
        vaultServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vaultServer.createContext("/v1/auth/approle/login", exchange ->
                respond(exchange, 200, "{\"auth\":{\"client_token\":\"hvs.test-token\"}}"));
        vaultServer.createContext("/v1/auth/token/lookup-self", exchange ->
                respond(exchange, 200, "{\"data\":{\"id\":\"s.test\"}}"));
        vaultServer.createContext("/v1/auth/token/revoke-self", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        vaultServer.createContext("/v1/secret/data", exchange ->
                respond(exchange, 200, vaultKvBody.get()));
        vaultServer.start();
        vaultBase = "http://127.0.0.1:" + vaultServer.getAddress().getPort();
    }

    private void applyManualVault(PortainerSwarmSecretBuilder step) {
        VaultManual manual = new VaultManual(vaultBase, "vault-approle");
        manual.setVaultPath("applications/example/systems/rabbitmq");
        step.setVault(manual);
    }

    private String pipelineSecretArgs(boolean validateOnly) {
        return "    endpointId: '1',\n"
                + "    vault: vaultManual(\n"
                + "      vaultUrl: '" + vaultBase + "',\n"
                + "      vaultAppRoleCredentialsId: 'vault-approle',\n"
                + "      vaultPath: 'applications/example/systems/rabbitmq'\n"
                + "    ),\n"
                + "    secretKeys: 'rabbitmq_signing_key'"
                + (validateOnly ? ",\n    validateOnly: true\n" : "\n");
    }

    private static JSONObject secretJson(JSONObject vault) {
        JSONObject json = new JSONObject();
        json.put("stapler-class", PortainerSwarmSecretBuilder.class.getName());
        json.put("$class", PortainerSwarmSecretBuilder.class.getName());
        json.put("endpointId", "1");
        json.put("secretKeys", "app_key");
        json.put("portainerConnectionMode", "inherit");
        json.put("vault", vault);
        return json;
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

    private static PortainerSwarmSecretBuilder loadBuilder(JenkinsRule jenkins, String resource)
            throws IOException {
        assertNotNull(jenkins.jenkins);
        try (InputStream in = PortainerSwarmSecretBuilderTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return (PortainerSwarmSecretBuilder) Items.XSTREAM2.fromXML(in);
        }
    }

    private static String vaultKvJson(String key, String value) {
        return "{\"data\":{\"data\":{\"" + key + "\":\"" + jsonEscape(value)
                + "\"},\"metadata\":{\"version\":1}}}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void configurePortainer(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "portainer-api-key",
                        "portainer",
                        Secret.fromString("test-token")));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-approle",
                        "AppRole",
                        "role-abc",
                        "secret-xyz"));
        SystemCredentialsProvider.getInstance().save();
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
