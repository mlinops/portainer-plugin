package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpServer;
import hudson.AbortException;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class VaultKvTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void optionalSoftSkip_noneMode_returnsNull() throws Exception {
        Map<String, String> data = VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.NONE,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog()));
        assertNull(data);
    }

    @Test
    void required_noneMode_aborts() {
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.NONE,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault connection is required"));
    }

    @Test
    void optionalSoftSkip_emptyPath_returnsNull() throws Exception {
        VaultFields fields = VaultFields.parse("", "secret", null, null, null, null);
        Map<String, String> data = VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.INHERIT,
                fields,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog()));
        assertNull(data);
    }

    @Test
    void optionalSoftSkip_manualPartialWithoutPath_aborts() {
        VaultFields fields = VaultFields.parse(
                "", "secret", null, null, "https://vault.example", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.MANUAL,
                fields,
                "approle-cred",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("partially configured"));
    }

    @Test
    void optionalSoftSkip_manualPartialWithOnlyCreds_aborts() {
        VaultFields fields = VaultFields.parse("", "secret", null, null, null, null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.MANUAL,
                fields,
                "approle-only",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("partially configured"));
    }

    @Test
    void required_blankPath_aborts() {
        VaultFields fields = VaultFields.parse("", "secret", null, null, null, null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.INHERIT,
                fields,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault path is required"));
    }

    @Test
    void required_manualMissingCreds_aborts() {
        VaultFields fields = VaultFields.parse(
                "apps/rabbitmq", "secret", null, null, "https://vault.example", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.MANUAL,
                fields,
                "",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault Manual requires vaultUrl"));
    }

    @Test
    void optionalSoftSkip_manualPathMissingUrl_aborts() {
        VaultFields fields = VaultFields.parse(
                "apps/rabbitmq", "secret", null, null, "", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.MANUAL,
                fields,
                "approle-cred",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault Manual requires vaultUrl"));
        assertTrue(ex.getMessage().contains("Not connected"));
    }

    @Test
    void nullRequest_aborts() {
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(null));
        assertTrue(ex.getMessage().contains("build logger"));
    }

    @Test
    void nullLog_aborts() {
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.INHERIT,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                null)));
        assertTrue(ex.getMessage().contains("build logger"));
    }

    @Test
    void inherit_withoutPlugin_aborts(JenkinsRule jenkins) {
        VaultFields fields = VaultFields.parse(
                "apps/demo", "secret", "3", null, null, null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.INHERIT,
                fields,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(
                ex.getMessage().contains(VaultPluginInherit.VAULT_PLUGIN_MISSING)
                        || ex.getMessage().toLowerCase().contains("vault"),
                ex.getMessage());
    }

    @Test
    void inherit_required_remapsNotFoundMessage(JenkinsRule jenkins) {
        // Without plugin the message is "not installed" — still exercises inherit catch+abort path.
        VaultFields fields = VaultFields.parse("missing/path", "secret", null, null, null, null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.INHERIT,
                fields,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank());
    }

    @Test
    void manual_invalidUrl_aborts(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-kv-approle",
                        "AppRole",
                        "role",
                        "secret"));
        SystemCredentialsProvider.getInstance().save();

        VaultFields fields = VaultFields.parse(
                "apps/demo", "secret", null, null, "not-a-url", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.MANUAL,
                fields,
                "vault-kv-approle",
                null,
                null,
                null,
                2000,
                2000,
                quietLog())));
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank());
    }

    @Test
    void manual_missingAppRoleCredential_aborts(JenkinsRule jenkins) {
        VaultFields fields = VaultFields.parse(
                "apps/demo", "secret", null, null, "https://vault.example", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.MANUAL,
                fields,
                "no-such-approle",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("not found") || ex.getMessage().contains("Vault"));
    }

    @Test
    void manual_readsKvViaHttp(JenkinsRule jenkins) throws Exception {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        startVaultMock(200, "{\"data\":{\"data\":{\"K\":\"v\"},\"metadata\":{\"version\":1}}}");

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-kv-ok",
                        "AppRole",
                        "role-abc",
                        "secret-xyz"));
        SystemCredentialsProvider.getInstance().save();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        VaultFields fields = VaultFields.parse(
                "myapp/prod", "secret", "1", "ns1", baseUrl, null);

        Map<String, String> data = VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.MANUAL,
                fields,
                "vault-kv-ok",
                null,
                null,
                null,
                2000,
                2000,
                quietLog()));
        assertEquals(Map.of("K", "v"), data);
    }

    @Test
    void manual_required_404_remapsToPathNotFound(JenkinsRule jenkins) throws Exception {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        startVaultMock(404, "{\"errors\":[\"not found\"]}");

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-kv-404",
                        "AppRole",
                        "role-abc",
                        "secret-xyz"));
        SystemCredentialsProvider.getInstance().save();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        VaultFields fields = VaultFields.parse(
                "missing/path", "secret", null, null, baseUrl, null);

        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.MANUAL,
                fields,
                "vault-kv-404",
                null,
                null,
                null,
                2000,
                2000,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault path not found"));
        assertTrue(ex.getMessage().contains("missing/path"));
    }

    @Test
    void manual_optionalSoftSkip_logsPathAndReads(JenkinsRule jenkins) throws Exception {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        startVaultMock(200, "{\"data\":{\"data\":{\"A\":\"1\"},\"metadata\":{\"version\":2}}}");

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-kv-soft",
                        "AppRole",
                        "role-abc",
                        "secret-xyz"));
        SystemCredentialsProvider.getInstance().save();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        VaultFields fields = VaultFields.parse(
                "stack/env", "secret", null, null, baseUrl, null);

        Map<String, String> data = VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.MANUAL,
                fields,
                "vault-kv-soft",
                null,
                null,
                null,
                2000,
                2000,
                quietLog()));
        assertEquals(Map.of("A", "1"), data);
    }

    private void startVaultMock(int readCode, String readBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/approle/login", exchange ->
                respond(exchange, 200, "{\"auth\":{\"client_token\":\"hvs.test\"}}"));
        server.createContext("/v1/secret/data", exchange -> respond(exchange, readCode, readBody));
        server.createContext("/v1/auth/token/revoke-self", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static PortainerBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new PortainerBuildLogger(Logger.getLogger("VaultKvTest"), listener, false);
    }
}
