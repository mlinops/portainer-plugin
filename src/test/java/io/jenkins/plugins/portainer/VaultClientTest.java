package io.jenkins.plugins.portainer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class VaultClientTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger loginCode = new AtomicInteger(200);
    private final AtomicInteger readCode = new AtomicInteger(200);
    private final AtomicInteger revokeCode = new AtomicInteger(204);
    private final AtomicReference<String> loginBody =
            new AtomicReference<>("{\"auth\":{\"client_token\":\"hvs.test-token\"}}");
    private final AtomicReference<String> readBody = new AtomicReference<>(
            "{\"data\":{\"data\":{\"IMAGE_TAG\":\"1.2.3\",\"DB_PASS\":\"s3cret\"},\"metadata\":{\"version\":2}}}");
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastRevokeToken = new AtomicReference<>();
    private final AtomicReference<String> lastLoginBody = new AtomicReference<>();
    private final AtomicReference<String> lastNamespace = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final List<String> callOrder = new ArrayList<>();
    private final AtomicInteger revokeHits = new AtomicInteger(0);

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        callOrder.clear();
        revokeHits.set(0);
        lastRevokeToken.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/approle/login", exchange -> {
            callOrder.add("login");
            lastPath.set(exchange.getRequestURI().getPath());
            lastLoginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastNamespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            respond(exchange, loginCode.get(), loginBody.get());
        });
        server.createContext("/v1/secret/data", exchange -> {
            callOrder.add("kv");
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            lastNamespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            respond(exchange, readCode.get(), readBody.get());
        });
        server.createContext("/v1/auth/token/lookup-self", exchange -> {
            callOrder.add("lookup-self");
            lastPath.set(exchange.getRequestURI().getPath());
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            lastNamespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            respond(exchange, 200, "{\"data\":{\"display_name\":\"approle\"}}");
        });
        server.createContext("/v1/sys/health", exchange -> {
            callOrder.add("health");
            lastPath.set(exchange.getRequestURI().getPath());
            lastNamespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            respond(exchange, 200, "{\"initialized\":true,\"sealed\":false,\"version\":\"1.15.0\"}");
        });
        server.createContext("/v1/auth/token/revoke-self", exchange -> {
            callOrder.add("revoke-self");
            revokeHits.incrementAndGet();
            lastRevokeToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            lastNamespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            // Vault often returns 204 with empty body for revoke-self
            int code = revokeCode.get();
            if (code == 204) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            respond(exchange, code, "{\"errors\":[\"revoke denied\"]}");
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
    public void readKvV2_loginAndReadFlatMap() throws Exception {
        try (VaultClient client = new VaultClient(2000, 2000)) {
        Map<String, String> secrets = client.readKvV2(new VaultClient.ReadRequest(
                base, "role-abc", "secret-xyz", "secret", "myapp/prod", null, null));
        assertEquals("1.2.3", secrets.get("IMAGE_TAG"));
        assertEquals("s3cret", secrets.get("DB_PASS"));
        assertEquals("hvs.test-token", lastToken.get());
        assertTrue(lastPath.get().endsWith("/v1/secret/data/myapp/prod"));
        assertTrue(lastLoginBody.get().contains("role_id"));
        // Response values must not appear in exception paths we care about; login body is request-only
        assertFalse(lastToken.get().isBlank());
        assertEquals(List.of("login", "kv", "revoke-self"), callOrder);
        assertEquals("hvs.test-token", lastRevokeToken.get());
        assertEquals(1, revokeHits.get());
        }
    }

    @Test
    public void readKvV2_sendsNamespaceAndVersion() throws Exception {
        try (VaultClient client = new VaultClient(2000, 2000)) {
        client.readKvV2(new VaultClient.ReadRequest(
                base, "role", "secret", "secret", "app", "ns1", 3));
        assertEquals("ns1", lastNamespace.get());
        assertEquals("version=3", lastQuery.get());
        assertEquals(List.of("login", "kv", "revoke-self"), callOrder);
        }
    }

    @Test
    public void readKvV2_revokeFailure_stillReturnsSecrets() throws Exception {
        revokeCode.set(500);
        try (VaultClient client = new VaultClient(2000, 2000)) {
        Map<String, String> secrets = client.readKvV2(new VaultClient.ReadRequest(
                base, "role-abc", "secret-xyz", "secret", "myapp/prod", null, null));
        assertEquals("1.2.3", secrets.get("IMAGE_TAG"));
        assertEquals("s3cret", secrets.get("DB_PASS"));
        assertEquals(List.of("login", "kv", "revoke-self"), callOrder);
        assertEquals("hvs.test-token", lastRevokeToken.get());
        assertEquals(1, revokeHits.get());
        }
    }

    @Test
    public void readKvV2_authFailure_noSecretsInMessage() {
        loginCode.set(403);
        loginBody.set("{\"errors\":[\"permission denied\"]}");
        try (VaultClient client = new VaultClient(2000, 2000)) {
        try {
            client.readKvV2(new VaultClient.ReadRequest(
                    base, "role-abc", "secret-xyz-VALUE", "secret", "myapp/prod", null, null));
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("403") || e.getMessage().toLowerCase().contains("permission"));
            assertFalse(e.getMessage().contains("secret-xyz-VALUE"));
            assertFalse(e.getMessage().contains("s3cret"));
            assertEquals(List.of("login"), callOrder);
            assertEquals(0, revokeHits.get());
        }
        }
    }

    @Test
    public void readKvV2_notFound_stillRevokes() {
        readCode.set(404);
        readBody.set("{\"errors\":[\"no secret\"]}");
        try (VaultClient client = new VaultClient(2000, 2000)) {
        IOException ex = assertThrows(IOException.class, () ->
                client.readKvV2(new VaultClient.ReadRequest(
                        base, "role", "secret", "secret", "missing", null, null)));
        assertTrue(ex.getMessage().toLowerCase().contains("not found")
                || ex.getMessage().contains("404"));
        assertFalse(ex.getMessage().contains("hvs.test-token"));
        assertEquals(List.of("login", "kv", "revoke-self"), callOrder);
        assertEquals("hvs.test-token", lastRevokeToken.get());
        }
    }

    @Test
    public void readKvV2_rejectsNonKvV2Shape() {
        readBody.set("{\"data\":{\"IMAGE_TAG\":\"x\"}}"); // KV v1-ish / missing data.data
        try (VaultClient client = new VaultClient(2000, 2000)) {
        IOException ex = assertThrows(IOException.class, () ->
                client.readKvV2(new VaultClient.ReadRequest(
                        base, "role", "secret", "secret", "myapp", null, null)));
        assertTrue(ex.getMessage().toLowerCase().contains("kv v2"));
        assertEquals(List.of("login", "kv", "revoke-self"), callOrder);
        }
    }

    @Test
    public void normalizeSecretPath_stripsDataPrefix() {
        assertEquals("myapp/prod", VaultClient.normalizeSecretPath("data/myapp/prod"));
        assertEquals("myapp/prod", VaultClient.normalizeSecretPath("/myapp/prod/"));
    }

    @Test
    public void normalizeMount_defaultAndRejectsSlash() {
        assertEquals("secret", VaultClient.normalizeMount(null));
        assertThrows(IllegalArgumentException.class, () -> VaultClient.normalizeMount("a/b"));
    }

    @Test
    public void preflightAppRole_loginLookupRevoke() throws Exception {
        try (VaultClient client = new VaultClient(2000, 2000)) {
        client.preflightAppRole(base, "role-abc", "secret-xyz", null);
        assertEquals(List.of("login", "lookup-self", "revoke-self"), callOrder);
        assertEquals("hvs.test-token", lastToken.get());
        assertEquals("hvs.test-token", lastRevokeToken.get());
        }
    }

    @Test
    public void probeHealth_unsealed() throws Exception {
        try (VaultClient client = new VaultClient(2000, 2000)) {
        client.probeHealth(base, null);
        assertEquals(List.of("health"), callOrder);
        assertTrue(lastPath.get().endsWith("/v1/sys/health"));
        }
    }

    @Test
    public void probeHealth_standby429_accepted() throws Exception {
        server.removeContext("/v1/sys/health");
        server.createContext("/v1/sys/health", exchange -> {
            callOrder.add("health");
            lastNamespace.set(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
            respond(exchange, 429, "{\"initialized\":true,\"sealed\":false,\"standby\":true}");
        });
        try (VaultClient client = new VaultClient(2000, 2000)) {
            client.probeHealth(base, "ns-health");
        }
        assertEquals("ns-health", lastNamespace.get());
        assertEquals(List.of("health"), callOrder);
    }

    @Test
    public void probeHealth_sealedFlag_aborts() {
        server.removeContext("/v1/sys/health");
        server.createContext("/v1/sys/health", exchange ->
                respond(exchange, 200, "{\"initialized\":true,\"sealed\":true}"));
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () -> client.probeHealth(base, null));
            assertTrue(ex.getMessage().toLowerCase().contains("sealed"));
        }
    }

    @Test
    public void probeHealth_uninitializedFlag_aborts() {
        server.removeContext("/v1/sys/health");
        server.createContext("/v1/sys/health", exchange ->
                respond(exchange, 200, "{\"initialized\":false,\"sealed\":false}"));
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () -> client.probeHealth(base, null));
            assertTrue(ex.getMessage().toLowerCase().contains("not initialized"));
        }
    }

    @Test
    public void probeHealth_http503_mappedToSealed() {
        server.removeContext("/v1/sys/health");
        server.createContext("/v1/sys/health", exchange ->
                respond(exchange, 503, "{\"errors\":[\"sealed\"]}"));
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () -> client.probeHealth(base, null));
            assertTrue(ex.getMessage().toLowerCase().contains("sealed"));
        }
    }

    @Test
    public void probeHealth_http501_mappedToUninitialized() {
        server.removeContext("/v1/sys/health");
        server.createContext("/v1/sys/health", exchange ->
                respond(exchange, 501, "{\"errors\":[\"not initialized\"]}"));
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () -> client.probeHealth(base, null));
            assertTrue(ex.getMessage().toLowerCase().contains("not initialized"));
        }
    }

    @Test
    public void readKvV2_rejectsNestedJsonValues() {
        readBody.set("{\"data\":{\"data\":{\"nested\":{\"a\":1}},\"metadata\":{}}}");
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () ->
                    client.readKvV2(new VaultClient.ReadRequest(
                            base, "role", "secret", "secret", "myapp", null, null)));
            assertTrue(ex.getMessage().toLowerCase().contains("nested")
                    || ex.getMessage().toLowerCase().contains("flat"));
            assertEquals(List.of("login", "kv", "revoke-self"), callOrder);
        }
    }

    @Test
    public void readKvV2_nullAndBlankKeys_andMissingDataWrapper() throws Exception {
        readBody.set("{\"data\":{\"data\":{\"\": \"x\", \"ok\": null},\"metadata\":{}}}");
        try (VaultClient client = new VaultClient(2000, 2000)) {
            Map<String, String> secrets = client.readKvV2(new VaultClient.ReadRequest(
                    base, "role", "secret", "secret", "myapp", null, null));
            assertEquals("", secrets.get("ok"));
            assertFalse(secrets.containsKey(""));
        }

        callOrder.clear();
        revokeHits.set(0);
        readBody.set("{\"auth\":{}}");
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () ->
                    client.readKvV2(new VaultClient.ReadRequest(
                            base, "role", "secret", "secret", "myapp", null, null)));
            assertTrue(ex.getMessage().toLowerCase().contains("kv v2"));
        }
    }

    @Test
    public void readKvV2_missingClientToken_aborts() {
        loginBody.set("{\"auth\":{}}");
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () ->
                    client.readKvV2(new VaultClient.ReadRequest(
                            base, "role", "secret", "secret", "myapp", null, null)));
            assertTrue(ex.getMessage().toLowerCase().contains("client token"));
            assertEquals(List.of("login"), callOrder);
            assertEquals(0, revokeHits.get());
        }
    }

    @Test
    public void readKvV2_blankRoleId_aborts() {
        try (VaultClient client = new VaultClient(2000, 2000)) {
            IOException ex = assertThrows(IOException.class, () ->
                    client.readKvV2(new VaultClient.ReadRequest(
                            base, " ", "secret", "secret", "myapp", null, null)));
            assertTrue(ex.getMessage().toLowerCase().contains("role_id"));
            assertEquals(List.of(), callOrder);
        }
    }

    @Test
    public void readKvV2_withBuildLog_revokeSoftFailLogged() throws Exception {
        revokeCode.set(403);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        PortainerBuildLogger log =
                new PortainerBuildLogger(java.util.logging.Logger.getLogger("VaultClientTest"), listener, true);
        try (VaultClient client = new VaultClient(2000, 2000, log)) {
            Map<String, String> secrets = client.readKvV2(new VaultClient.ReadRequest(
                    base, "role", "secret", "secret", "myapp/prod", null, null));
            assertEquals("1.2.3", secrets.get("IMAGE_TAG"));
        }
        String console = buf.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("revoke-self") || console.toLowerCase().contains("revoke"));
    }

    @Test
    public void httpError_andExtractErrors_branches() {
        assertTrue(VaultClient.httpError(401, new byte[0], "login").getMessage().contains("401"));
        assertTrue(VaultClient.httpError(403, "{\"errors\":[\"denied\"]}".getBytes(StandardCharsets.UTF_8), null)
                .getMessage()
                .contains("permission"));
        assertTrue(VaultClient.httpError(404, "{\"message\":\"gone\"}".getBytes(StandardCharsets.UTF_8), "KV")
                .getMessage()
                .contains("404"));
        assertTrue(VaultClient.httpError(500, "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8), "op")
                .getMessage()
                .contains("500"));

        assertEquals("", VaultClient.extractErrors(null));
        assertEquals("", VaultClient.extractErrors(new byte[0]));
        assertEquals("", VaultClient.extractErrors("not-json".getBytes(StandardCharsets.UTF_8)));
        assertTrue(VaultClient.extractErrors("{\"errors\":[\"a\",\"b\"]}".getBytes(StandardCharsets.UTF_8))
                .contains("a"));
        // secret-looking error text is suppressed
        assertEquals(
                "",
                VaultClient.extractErrors(
                        "{\"errors\":[\"client_token=hvs.abc\"]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                "",
                VaultClient.extractErrors(
                        "{\"message\":\"role_id leaked\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void normalizeAndParseHelpers() {
        assertEquals("secret", VaultClient.normalizeMount(""));
        assertEquals("secret", VaultClient.normalizeMount("  /secret/  "));
        assertThrows(IllegalArgumentException.class, () -> VaultClient.normalizeMount("///"));

        assertEquals("app", VaultClient.normalizeSecretPath("data/app"));
        assertEquals("a/b", VaultClient.normalizeSecretPath("/a/b/"));
        assertEquals("data", VaultClient.normalizeSecretPath("data/"));
        assertThrows(IllegalArgumentException.class, () -> VaultClient.normalizeSecretPath(""));
        assertThrows(IllegalArgumentException.class, () -> VaultClient.normalizeSecretPath("../x"));

        assertNull(VaultClient.parseVersion(null));
        assertNull(VaultClient.parseVersion("  "));
        assertEquals(3, VaultClient.parseVersion("3"));
        assertThrows(IllegalArgumentException.class, () -> VaultClient.parseVersion("0"));
        assertThrows(IllegalArgumentException.class, () -> VaultClient.parseVersion("x"));

        assertEquals("a/b", VaultClient.stripSurroundingSlashes("/a/b/"));
        assertEquals("", VaultClient.stripSurroundingSlashes("///"));
        assertEquals("a", VaultClient.stripLeadingSlashes("///a"));
        assertEquals("a", VaultClient.stripTrailingSlashes("a///"));
        assertNull(VaultClient.stripTrailingSlashes(null));
        assertEquals("", VaultClient.stripTrailingSlashes(""));
    }

    @Test
    public void preflightAppRole_sendsNamespace() throws Exception {
        try (VaultClient client = new VaultClient(2000, 2000)) {
            client.preflightAppRole(base, "role-abc", "secret-xyz", "enterprise-ns");
            assertEquals("enterprise-ns", lastNamespace.get());
            assertEquals(List.of("login", "lookup-self", "revoke-self"), callOrder);
        }
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
