package io.jenkins.plugins.portainer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
