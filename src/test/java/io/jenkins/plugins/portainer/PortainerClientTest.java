package io.jenkins.plugins.portainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class PortainerClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicInteger endpointsCode = new AtomicInteger(200);
    private final AtomicReference<String> statusBody =
            new AtomicReference<>("{\"Version\":\"2.39.3\"}");
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger createCode = new AtomicInteger(200);
    private final AtomicReference<String> createBody =
            new AtomicReference<>("{\"Id\":42,\"Name\":\"myapp\",\"EndpointId\":1}");
    private final AtomicInteger swarmCode = new AtomicInteger(200);
    private final AtomicInteger stacksCode = new AtomicInteger(200);
    private final AtomicInteger redeployCode = new AtomicInteger(200);
    private final AtomicInteger helmListCode = new AtomicInteger(200);
    private final AtomicReference<String> helmListBody =
            new AtomicReference<>("[{\"Name\":\"nginx\",\"Namespace\":\"default\"}]");
    private final AtomicInteger k8sVersionCode = new AtomicInteger(200);
    private final AtomicReference<String> k8sVersionBody =
            new AtomicReference<>("{\"major\":\"1\",\"minor\":\"28\",\"gitVersion\":\"v1.28.0\"}");
    private final AtomicInteger namespaceGetCode = new AtomicInteger(200);
    private final AtomicReference<String> namespaceGetBody =
            new AtomicReference<>("{\"Name\":\"apps\",\"IsSystem\":false}");
    private final AtomicInteger namespaceCreateCode = new AtomicInteger(200);
    private final AtomicReference<String> namespaceCreateBody =
            new AtomicReference<>("{\"Name\":\"apps\",\"IsSystem\":false}");

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        statusCode.set(200);
        endpointsCode.set(200);
        statusBody.set("{\"Version\":\"2.39.3\"}");
        createCode.set(200);
        createBody.set("{\"Id\":42,\"Name\":\"myapp\",\"EndpointId\":1}");
        swarmCode.set(200);
        stacksCode.set(200);
        redeployCode.set(200);
        helmListCode.set(200);
        helmListBody.set("[{\"Name\":\"nginx\",\"Namespace\":\"default\"}]");
        k8sVersionCode.set(200);
        k8sVersionBody.set("{\"major\":\"1\",\"minor\":\"28\",\"gitVersion\":\"v1.28.0\"}");
        namespaceGetCode.set(200);
        namespaceGetBody.set("{\"Name\":\"apps\",\"IsSystem\":false}");
        namespaceCreateCode.set(200);
        namespaceCreateBody.set("{\"Name\":\"apps\",\"IsSystem\":false}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/status", exchange -> {
            capture(exchange);
            respond(exchange, statusCode.get(), statusBody.get());
        });
        server.createContext("/api/endpoints", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path != null && path.contains("/docker/swarm")) {
                respond(exchange, swarmCode.get(), "{\"ID\":\"swarm-abc-123\"}");
                return;
            }
            // GET /api/endpoints/{id} — single environment
            if (path != null && path.matches(".*/api/endpoints/\\d+$")) {
                respond(exchange, endpointsCode.get(), "{\"Id\":1,\"Name\":\"local\",\"Type\":1}");
                return;
            }
            respond(exchange, endpointsCode.get(), "[{\"Id\":1,\"Name\":\"local\"}]");
        });
        server.createContext("/api/stacks/create/standalone/repository", exchange -> {
            capture(exchange);
            respond(exchange, createCode.get(), createBody.get());
        });
        server.createContext("/api/stacks/create/standalone/string", exchange -> {
            capture(exchange);
            respond(exchange, createCode.get(), createBody.get());
        });
        server.createContext("/api/stacks/create/swarm/repository", exchange -> {
            capture(exchange);
            respond(exchange, createCode.get(), "{\"Id\":43,\"Name\":\"swarmapp\",\"EndpointId\":1}");
        });
        server.createContext("/api/stacks/create/swarm/string", exchange -> {
            capture(exchange);
            respond(exchange, createCode.get(), "{\"Id\":44,\"Name\":\"swarmapp\",\"EndpointId\":1}");
        });
        server.createContext("/api/stacks/create/kubernetes/string", exchange -> {
            capture(exchange);
            respond(exchange, createCode.get(), "{\"Output\":\"ok\"}");
        });
        server.createContext("/api/stacks/create/kubernetes/repository", exchange -> {
            capture(exchange);
            respond(exchange, createCode.get(), "{\"Output\":\"ok\"}");
        });
        server.createContext("/api/endpoints/1/kubernetes/helm", exchange -> {
            capture(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, helmListCode.get(), helmListBody.get());
                return;
            }
            respond(exchange, 201, "{\"name\":\"nginx\"}");
        });
        server.createContext("/api/endpoints/1/kubernetes/version", exchange -> {
            capture(exchange);
            respond(exchange, k8sVersionCode.get(), k8sVersionBody.get());
        });
        server.createContext("/api/endpoints/1/kubernetes/helm/nginx", exchange -> {
            capture(exchange);
            respond(exchange, 200, "{}");
        });
        server.createContext("/api/endpoints/1/docker/configs", exchange -> {
            capture(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(
                        exchange,
                        200,
                        "[{\"ID\":\"cfg1\",\"Spec\":{\"Name\":\"app-07d8fcbc\",\"Labels\":"
                                + "{\"jenkins.portainer.config/base\":\"app\",\"jenkins.portainer.config/hash\":\"07d8fcbc\"}}}]");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/api/endpoints/1/docker/configs/create", exchange -> {
            capture(exchange);
            respond(exchange, 200, "{\"ID\":\"cfg-new\"}");
        });
        server.createContext("/api/endpoints/1/docker/configs/cfg-old", exchange -> {
            capture(exchange);
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 200, "{}");
                return;
            }
            respond(exchange, 405, "{}");
        });
        server.createContext("/api/endpoints/1/docker/secrets", exchange -> {
            capture(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(
                        exchange,
                        200,
                        "[{\"ID\":\"sec1\",\"Spec\":{\"Name\":\"key-2cf24dba5fb0a30e\",\"Labels\":"
                                + "{\"jenkins.portainer.secret/base\":\"key\",\"jenkins.portainer.secret/hash\":\"2cf24dba5fb0a30e\"}}}]");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/api/endpoints/1/docker/secrets/create", exchange -> {
            capture(exchange);
            respond(exchange, 200, "{\"ID\":\"sec-new\"}");
        });
        server.createContext("/api/endpoints/1/docker/secrets/sec-old", exchange -> {
            capture(exchange);
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 200, "{}");
                return;
            }
            respond(exchange, 405, "{}");
        });
        server.createContext("/api/kubernetes", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path != null
                    && path.matches(".*/api/kubernetes/\\d+/namespaces/[^/]+$")
                    && "GET".equalsIgnoreCase(method)) {
                respond(exchange, namespaceGetCode.get(), namespaceGetBody.get());
                return;
            }
            if (path != null
                    && path.matches(".*/api/kubernetes/\\d+/namespaces$")
                    && "POST".equalsIgnoreCase(method)) {
                respond(exchange, namespaceCreateCode.get(), namespaceCreateBody.get());
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/api/stacks", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path != null && path.contains("/git/redeploy")) {
                respond(exchange, redeployCode.get(), "{\"Id\":7,\"Name\":\"myapp\",\"EndpointId\":1}");
                return;
            }
            // PUT /api/stacks/{id}
            if (path != null && path.matches(".*/api/stacks/\\d+$")
                    && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, redeployCode.get(), "{\"Id\":7,\"Name\":\"myapp\",\"EndpointId\":1}");
                return;
            }
            // GET /api/stacks/{id}
            if (path != null && path.matches(".*/api/stacks/\\d+$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(
                        exchange,
                        200,
                        "{\"Id\":7,\"Name\":\"myapp\",\"EndpointId\":1,"
                                + "\"Env\":[{\"name\":\"KEEP\",\"value\":\"old\"},"
                                + "{\"Name\":\"UPDATE\",\"Value\":\"from-portainer\"}]}");
                return;
            }
            respond(
                    exchange,
                    stacksCode.get(),
                    "[{\"Id\":7,\"Name\":\"myapp\",\"EndpointId\":1},"
                            + "{\"Id\":8,\"Name\":\"other\",\"EndpointId\":2}]");
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
    public void probeAccess_success_sendsApiKeyHeader() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.ProbeDetails details = client.probeAccess(base, "test-api-key");
        assertEquals("Portainer v2.39.3", details.primaryLabel());
        assertEquals("test-api-key", lastApiKey.get());
        assertFalse(details.primaryLabel().contains("environment"));
        assertFalse(details.primaryLabel().contains("test-api-key"));
        }
    }

    @Test
    public void probeAccess_mapsHttp401() {
        statusCode.set(401);
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        try {
            client.probeAccess(base, "bad");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("401"));
            assertFalse(e.getMessage().contains("bad"));
        }
        }
    }

    @Test
    public void probeAccess_fallbackToEndpointsWhenStatusMissing() throws Exception {
        statusCode.set(404);
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.ProbeDetails details = client.probeAccess(base, "key");
        assertEquals("Portainer endpoints reachable", details.primaryLabel());
        assertFalse(details.primaryLabel().contains("environment"));
        }
    }

    @Test
    public void probeAccess_requiresApiKey() {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        try {
            client.probeAccess(base, " ");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().toLowerCase().contains("api key"));
        }
        }
    }

    @Test
    public void getEndpoint_returnsEndpointJson() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        JsonNode endpoint = client.getEndpoint(base, "api-key", 1);
        assertEquals(1, endpoint.path("Id").asInt());
        assertEquals("local", endpoint.path("Name").asText());
        assertTrue(lastPath.get().endsWith("/api/endpoints/1"));
        assertEquals("api-key", lastApiKey.get());
        }
    }

    @Test
    public void getEndpoint_maps404ToClearMessage() {
        endpointsCode.set(404);
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        try {
            client.getEndpoint(base, "api-key", 99);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("endpoint ID 99"));
            assertTrue(e.getMessage().toLowerCase().contains("not found")
                    || e.getMessage().toLowerCase().contains("not available"));
            assertFalse(e.getMessage().contains("api-key"));
        }
        }
    }

    @Test
    public void createStandaloneStackFromRepository_sendsBodyWithoutTlsSkipVerify() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFromGitRequest req = new PortainerClient.StackFromGitRequest(
                "myapp",
                "https://gitlab.example/group/stack.git",
                "docker-compose.yml",
                "refs/heads/main",
                "gituser",
                "git-secret",
                List.of(new PortainerClient.EnvPair("IMAGE_TAG", "9")));
        JsonNode created = client.createStandaloneStackFromRepository(base, "api-key", 1, req);
        assertEquals(42, created.path("Id").asInt());
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().contains("/api/stacks/create/standalone/repository"));
        assertTrue(lastQuery.get().contains("endpointId=1"));
        assertEquals("api-key", lastApiKey.get());

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("myapp", body.path("Name").asText());
        assertEquals("https://gitlab.example/group/stack.git", body.path("RepositoryURL").asText());
        assertEquals("docker-compose.yml", body.path("ComposeFile").asText());
        assertEquals("refs/heads/main", body.path("RepositoryReferenceName").asText());
        assertTrue(body.path("RepositoryAuthentication").asBoolean());
        assertEquals("gituser", body.path("RepositoryUsername").asText());
        assertEquals("git-secret", body.path("RepositoryPassword").asText());
        assertEquals("IMAGE_TAG", body.path("Env").get(0).path("name").asText());
        assertFalse(body.has("TLSSkipVerify"));
        assertFalse(lastBody.get().contains("TLSSkipVerify"));
        }
    }

    @Test
    public void createStandaloneStackFromString_sendsStackFileContent() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        String yaml = "services:\n  web:\n    image: nginx:alpine\n";
        PortainerClient.StackFromStringRequest req = new PortainerClient.StackFromStringRequest(
                "myapp", yaml, List.of(new PortainerClient.EnvPair("IMAGE_TAG", "9")));
        JsonNode created = client.createStandaloneStackFromString(base, "api-key", 1, req);
        assertEquals(42, created.path("Id").asInt());
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().contains("/api/stacks/create/standalone/string"));
        assertTrue(lastQuery.get().contains("endpointId=1"));

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("myapp", body.path("Name").asText());
        assertEquals(yaml, body.path("StackFileContent").asText());
        assertEquals("IMAGE_TAG", body.path("Env").get(0).path("name").asText());
        assertFalse(body.has("TLSSkipVerify"));
        assertFalse(body.has("RepositoryURL"));
        }
    }

    @Test
    public void createSwarmStackFromString_includesSwarmId() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFromStringRequest req = new PortainerClient.StackFromStringRequest(
                "swarmapp",
                "services:\n  web:\n    image: nginx:alpine\n",
                Collections.emptyList());
        JsonNode created = client.createSwarmStackFromString(base, "k", 3, req);
        assertEquals(44, created.path("Id").asInt());
        assertTrue(lastPath.get().contains("/api/stacks/create/swarm/string"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("swarm-abc-123", body.path("SwarmID").asText());
        assertTrue(body.path("StackFileContent").asText().contains("services"));
        }
    }

    @Test
    public void updateStackFileContent_putsPruneAndRepull() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFileUpdateRequest req = new PortainerClient.StackFileUpdateRequest(
                "services:\n  web:\n    image: nginx:alpine\n",
                List.of(new PortainerClient.EnvPair("A", "1")),
                true,
                true);
        JsonNode result = client.updateStackFileContent(base, "k", 7, 1, req);
        assertEquals(7, result.path("Id").asInt());
        assertEquals("PUT", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/api/stacks/7"));
        assertTrue(lastQuery.get().contains("endpointId=1"));
        assertFalse(lastPath.get().contains("git"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertTrue(body.path("StackFileContent").asText().contains("nginx"));
        assertTrue(body.path("Prune").asBoolean());
        assertTrue(body.path("RepullImageAndRedeploy").asBoolean());
        assertEquals("A", body.path("Env").get(0).path("name").asText());
        }
    }

    @Test
    public void updateStackFileContent_mapsGitStackRejection() throws Exception {
        redeployCode.set(400);
        // Custom body for PUT via temporary override: recreate stack handler is complex;
        // use a one-shot server path by stopping and adding error body on stacks context.
        server.stop(0);
        AtomicReference<String> errBody =
                new AtomicReference<>("{\"message\":\"this stack is a git repository stack\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/stacks", exchange -> {
            capture(exchange);
            respond(exchange, 400, errBody.get());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFileUpdateRequest req = new PortainerClient.StackFileUpdateRequest(
                "services:\n  web:\n    image: nginx:alpine\n",
                Collections.emptyList(),
                false,
                false);
        try {
            client.updateStackFileContent(base, "k", 7, 1, req);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().toLowerCase().contains("git-backed")
                    || e.getMessage().toLowerCase().contains("git"));
            assertTrue(e.getMessage().contains("Manual YAML") || e.getMessage().contains("Repository"));
        }
        }
    }

    @Test
    public void createSwarmStackFromRepository_resolvesSwarmId() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFromGitRequest req = new PortainerClient.StackFromGitRequest(
                "swarmapp",
                "https://gitlab.example/group/stack.git",
                "compose.yml",
                null,
                null,
                null,
                Collections.emptyList());
        JsonNode created = client.createSwarmStackFromRepository(base, "k", 3, req);
        assertEquals(43, created.path("Id").asInt());
        assertTrue(lastPath.get().contains("/api/stacks/create/swarm/repository"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("swarm-abc-123", body.path("SwarmID").asText());
        assertFalse(body.has("TLSSkipVerify"));
        }
    }

    @Test
    public void findStackIdByName_andGitRedeploy() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        int id = client.findStackIdByName(base, "k", "myapp", 1);
        assertEquals(7, id);
        assertEquals(-1, client.findStackIdByName(base, "k", "myapp", 99));

        PortainerClient.GitRedeployRequest redeploy = new PortainerClient.GitRedeployRequest(
                List.of(new PortainerClient.EnvPair("A", "1")),
                true,
                true,
                "refs/heads/main",
                "u",
                "p");
        JsonNode result = client.gitRedeploy(base, "k", 7, 1, redeploy);
        assertEquals(7, result.path("Id").asInt());
        assertEquals("PUT", lastMethod.get());
        assertTrue(lastPath.get().contains("/api/stacks/7/git/redeploy"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertTrue(body.path("Prune").asBoolean());
        assertTrue(body.path("RepullImageAndRedeploy").asBoolean());
        assertFalse(body.has("TLSSkipVerify"));
        assertFalse(lastBody.get().contains("TLSSkipVerify"));
        }
    }

    @Test
    public void getStackEnv_parsesNameValueVariants() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        List<PortainerClient.EnvPair> env = client.getStackEnv(base, "k", 7);
        assertEquals(2, env.size());
        assertEquals("KEEP", env.get(0).name);
        assertEquals("old", env.get(0).value);
        assertEquals("UPDATE", env.get(1).name);
        assertEquals("from-portainer", env.get(1).value);
        assertEquals("GET", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/api/stacks/7"));
        }
    }

    @Test
    public void parseStackEnv_emptyOrMissing() throws Exception {
        assertTrue(PortainerClient.parseStackEnv(null).isEmpty());
        assertTrue(PortainerClient.parseStackEnv(MAPPER.readTree("{}")).isEmpty());
        assertTrue(PortainerClient.parseStackEnv(MAPPER.readTree("{\"Env\":null}")).isEmpty());
    }

    @Test
    public void createKubernetesStackFromString_sendsStackNameAndNamespace() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.KubernetesFromStringRequest req =
                new PortainerClient.KubernetesFromStringRequest(
                        "web", "apiVersion: v1\nkind: ConfigMap\n", "apps");
        JsonNode created = client.createKubernetesStackFromString(base, "api-key", 1, req);
        assertEquals("ok", created.path("Output").asText());
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().contains("/api/stacks/create/kubernetes/string"));
        assertTrue(lastQuery.get().contains("endpointId=1"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("web", body.path("StackName").asText());
        assertEquals("apps", body.path("Namespace").asText());
        assertTrue(body.path("StackFileContent").asText().contains("ConfigMap"));
        assertFalse(body.has("TLSSkipVerify"));
        }
    }

    @Test
    public void createKubernetesStackFromString_omitsBlankStackName() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            PortainerClient.KubernetesFromStringRequest req =
                    new PortainerClient.KubernetesFromStringRequest(
                            "", "apiVersion: v1\nkind: ConfigMap\n", "default");
            client.createKubernetesStackFromString(base, "api-key", 1, req);
            JsonNode body = MAPPER.readTree(lastBody.get());
            assertFalse(body.has("StackName"));
            assertTrue(body.path("StackFileContent").asText().contains("ConfigMap"));
        }
    }

    @Test
    public void createKubernetesStackFromRepository_sendsManifestFile() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.KubernetesFromGitRequest req = new PortainerClient.KubernetesFromGitRequest(
                "web",
                "https://gitlab.example/group/m.git",
                "deploy.yaml",
                "refs/heads/main",
                "default",
                "u",
                "p");
        client.createKubernetesStackFromRepository(base, "k", 1, req);
        assertTrue(lastPath.get().contains("/kubernetes/repository"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("deploy.yaml", body.path("ManifestFile").asText());
        assertEquals("web", body.path("StackName").asText());
        assertTrue(body.path("RepositoryAuthentication").asBoolean());
        assertFalse(body.has("TLSSkipVerify"));
        }
    }

    @Test
    public void updateKubernetesStackFileContent_putsStackFileContent() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        client.updateKubernetesStackFileContent(
                base,
                "k",
                7,
                1,
                new PortainerClient.KubernetesFileUpdateRequest("kind: Pod\n", "web"));
        assertEquals("PUT", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/api/stacks/7"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("web", body.path("StackName").asText());
        assertTrue(body.path("StackFileContent").asText().contains("Pod"));
        }
    }

    @Test
    public void helmInstallAndUninstall_roundTrip() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        assertTrue(client.helmReleaseExists(base, "k", 1, "nginx", "default"));
        client.installHelmChart(
                base,
                "k",
                1,
                new PortainerClient.HelmInstallRequest(
                        "nginx",
                        "nginx",
                        "https://charts.example/bitnami",
                        "default",
                        "1.2.3",
                        "replicaCount: 2\n",
                        true));
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/kubernetes/helm"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("nginx", body.path("name").asText());
        assertEquals("nginx", body.path("chart").asText());
        assertEquals("https://charts.example/bitnami", body.path("repo").asText());
        assertEquals("1.2.3", body.path("version").asText());
        assertTrue(body.path("atomic").asBoolean());
        assertFalse(body.has("TLSSkipVerify"));

        client.uninstallHelmRelease(base, "k", 1, "nginx", "default");
        assertEquals("DELETE", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/kubernetes/helm/nginx"));
        assertTrue(lastQuery.get() != null && lastQuery.get().contains("namespace=default"));
        }
    }

    @Test
    public void ensureNamespace_whenExists_returnsExisted() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        assertEquals("existed", client.ensureNamespace(base, "k", 1, "apps"));
        assertEquals("GET", lastMethod.get());
        assertTrue(lastPath.get().contains("/api/kubernetes/1/namespaces/apps"));
        }
    }

    @Test
    public void ensureNamespace_whenMissing_creates() throws Exception {
        namespaceGetCode.set(404);
        namespaceGetBody.set("{\"message\":\"not found\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        assertEquals("created", client.ensureNamespace(base, "k", 1, "apps"));
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/api/kubernetes/1/namespaces"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("apps", body.path("Name").asText());
        }
    }

    @Test
    public void ensureNamespace_createConflict_returnsAlreadyExists() throws Exception {
        namespaceGetCode.set(404);
        namespaceGetBody.set("{\"message\":\"not found\"}");
        namespaceCreateCode.set(409);
        namespaceCreateBody.set("{\"message\":\"namespace already exists\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        assertEquals("already-exists", client.ensureNamespace(base, "k", 1, "apps"));
        }
    }

    @Test
    public void ensureNamespace_maps403WithReadableMessage() {
        namespaceGetCode.set(403);
        namespaceGetBody.set("{\"message\":\"forbidden\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        try {
            client.ensureNamespace(base, "k", 1, "apps");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Cannot ensure namespace \"apps\""));
            assertTrue(e.getMessage().toLowerCase().contains("lacks permission")
                    || e.getMessage().contains("403"));
            assertFalse(e.getMessage().contains("api-key"));
        }
        }
    }

    @Test
    public void listDockerConfigs_parsesSpecNameAndLabels() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        List<PortainerClient.DockerConfigSummary> configs = client.listDockerConfigs(base, "k", 1);
        assertEquals(1, configs.size());
        assertEquals("app-07d8fcbc", configs.get(0).name);
        assertEquals("app", configs.get(0).labels.get("jenkins.portainer.config/base"));
        assertTrue(lastPath.get().endsWith("/docker/configs"));
        }
    }

    @Test
    public void createDockerConfig_sendsBase64DataAndLabels() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        byte[] data = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> labels = Map.of(
                "jenkins.portainer.config/base", "app-settings",
                "jenkins.portainer.config/hash", "07d8fcbc");
        client.createDockerConfig(
                base, "k", 1, new PortainerClient.DockerConfigCreateRequest("app-settings-07d8fcbc", data, labels));
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/docker/configs/create"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("app-settings-07d8fcbc", body.path("Name").asText());
        assertEquals("eyJhIjoxfQ==", body.path("Data").asText());
        assertEquals("app-settings", body.path("Labels").path("jenkins.portainer.config/base").asText());
        }
    }

    @Test
    public void dockerCreateRequests_defensiveCopyOfData() {
        byte[] data = "secret".getBytes(StandardCharsets.UTF_8);
        PortainerClient.DockerConfigCreateRequest cfg =
                new PortainerClient.DockerConfigCreateRequest("n", data, Map.of());
        PortainerClient.DockerSecretCreateRequest sec =
                new PortainerClient.DockerSecretCreateRequest("n", data, Map.of());
        data[0] = 'X';
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), cfg.data);
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), sec.data);
    }

    @Test
    public void removeDockerConfig_sendsDelete() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        client.removeDockerConfig(base, "k", 1, "cfg-old");
        assertEquals("DELETE", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/docker/configs/cfg-old"));
        }
    }

    @Test
    public void listDockerSecrets_parsesSpecNameAndLabels() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        List<PortainerClient.DockerConfigSummary> secrets = client.listDockerSecrets(base, "k", 1);
        assertEquals(1, secrets.size());
        assertEquals("key-2cf24dba5fb0a30e", secrets.get(0).name);
        assertEquals("key", secrets.get(0).labels.get("jenkins.portainer.secret/base"));
        assertTrue(lastPath.get().endsWith("/docker/secrets"));
        }
    }

    @Test
    public void createDockerSecret_sendsNameAndLabelsWithoutLoggingConcern() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        client.createDockerSecret(
                base,
                "k",
                1,
                new PortainerClient.DockerSecretCreateRequest(
                        "key-2cf24dba5fb0a30e",
                        data,
                        Map.of("jenkins.portainer.secret/base", "key")));
        assertEquals("POST", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/docker/secrets/create"));
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertEquals("key-2cf24dba5fb0a30e", body.path("Name").asText());
        assertEquals("aGVsbG8=", body.path("Data").asText());
        assertEquals("key", body.path("Labels").path("jenkins.portainer.secret/base").asText());
        }
    }

    @Test
    public void removeDockerSecret_sendsDelete() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        client.removeDockerSecret(base, "k", 1, "sec-old");
        assertEquals("DELETE", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/docker/secrets/sec-old"));
        }
    }

    @Test
    public void createStandalone_maps401WithoutLeakingKey() {
        createCode.set(401);
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFromGitRequest req = new PortainerClient.StackFromGitRequest(
                "myapp",
                "https://gitlab.example/group/stack.git",
                "docker-compose.yml",
                null,
                null,
                null,
                Collections.emptyList());
        try {
            client.createStandaloneStackFromRepository(base, "super-secret-key", 1, req);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("401"));
            assertFalse(e.getMessage().contains("super-secret-key"));
        }
        }
    }

    @Test
    public void createStandalone_maps500WithPortainerMessage() {
        createCode.set(500);
        createBody.set("{\"message\":\"unable to clone git repository\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFromGitRequest req = new PortainerClient.StackFromGitRequest(
                "myapp",
                "https://gitlab.example/group/stack.git",
                "docker-compose.yml",
                null,
                null,
                null,
                Collections.emptyList());
        try {
            client.createStandaloneStackFromRepository(base, "api-key", 1, req);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("HTTP 500"));
            assertTrue(e.getMessage().contains("unable to clone git repository"));
            assertFalse(e.getMessage().contains("api-key"));
        }
        }
    }

    @Test
    public void extractErrorDetail_readsMessageJson() {
        assertEquals(
                "boom",
                PortainerClient.extractErrorDetail("{\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", PortainerClient.extractErrorDetail(new byte[0]));
    }

    @Test
    public void extractErrorDetail_combinesMessageAndDetails() {
        String body = "{\"message\":\"Helm returned an error\","
                + "\"details\":\"failed to list releases: namespaces \\\"kube-system\\\" is forbidden\"}";
        String extracted = PortainerClient.extractErrorDetail(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(extracted.contains("Helm returned an error"));
        assertTrue(extracted.contains("forbidden"));
        assertTrue(extracted.contains(" — "));
    }

    @Test
    public void extractErrorDetail_readsErrorsArray() {
        assertEquals(
                "one; two",
                PortainerClient.extractErrorDetail(
                        "{\"errors\":[\"one\",\"two\"]}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void extractErrorDetail_redactsApiKeyLikeTokens() {
        String body = "{\"message\":\"auth failed for ptr_ABCdef1234567890abcdefghijklmnop\"}";
        String extracted = PortainerClient.extractErrorDetail(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(extracted.contains("ptr_[redacted]"));
        assertFalse(extracted.contains("ptr_ABCdef"));
    }

    @Test
    public void listHelm_maps500WithPortainerMessageAndDetails() {
        helmListCode.set(500);
        helmListBody.set("{\"message\":\"Helm returned an error\","
                + "\"details\":\"helm list failed: connection refused\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        try {
            client.listHelmReleases(base, "api-key", 1, "default");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("HTTP 500"));
            assertTrue(e.getMessage().contains("Helm returned an error"));
            assertTrue(e.getMessage().contains("connection refused"));
            assertFalse(e.getMessage().contains("api-key"));
        }
        }
    }

    @Test
    public void probeKubernetesVersion_returnsVersionJson() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        JsonNode version = client.probeKubernetesVersion(base, "api-key", 1);
        assertEquals("1", version.path("major").asText());
        assertTrue(lastPath.get().endsWith("/kubernetes/version"));
        }
    }

    @Test
    public void httpError_kubernetesUnreachable_includesHint() {
        String body = "{\"message\":\"Helm returned an error\","
                + "\"details\":\"Failed to list helm releases: Kubernetes cluster unreachable: "
                + "Get \\\"https://portainer.test.local:9000/api/endpoints/326/kubernetes/version\\\": "
                + "http: server gave HTTP response to HTTPS client\"}";
        IOException err = PortainerClient.httpError(500, body.getBytes(StandardCharsets.UTF_8));
        String msg = err.getMessage();
        assertTrue(msg.contains("Kubernetes cluster unreachable from Portainer"));
        assertTrue(msg.contains("Hint:"));
        assertTrue(msg.contains("fix in Portainer"));
        assertTrue(msg.contains("TLS mismatch"));
        assertTrue(msg.contains("9000"));
    }

    @Test
    public void kubernetesConnectivityHint_emptyForUnrelatedErrors() {
        assertEquals("", PortainerClient.kubernetesConnectivityHint("stack conflict"));
        assertFalse(PortainerClient.isKubernetesConnectivityFailure("HTTP 404 not found"));
    }

    @Test
    public void kubernetesConnectivityHint_detectsClusterUnreachable() {
        String hint = PortainerClient.kubernetesConnectivityHint("Kubernetes cluster unreachable: dial tcp");
        assertTrue(hint.contains("Hint:"));
        assertTrue(hint.contains("fix in Portainer"));
        assertFalse(hint.contains("Public URL"));
    }

    @Test
    public void kubernetesConnectivityHint_localhostSanMismatch() {
        String detail =
                "Kubernetes cluster unreachable: Get \"https://localhost:9443/api/endpoints/326/kubernetes/version\":"
                        + " tls: failed to verify certificate: x509: certificate is valid for *.best.local, not localhost";
        String hint = PortainerClient.kubernetesConnectivityHint(detail);
        assertTrue(hint.contains("DNS:localhost"));
        assertTrue(hint.contains("Public URL / Jenkins Portainer URL do not change this"));
    }

    @Test
    public void kubernetesConnectivityHint_emptyWhenHintAlreadyPresent() {
        String already =
                "HTTP 500 - Kubernetes cluster unreachable from Portainer - … not localhost Hint: fix in Portainer";
        assertEquals("", PortainerClient.kubernetesConnectivityHint(already));
    }

    @Test
    public void extractErrorDetail_keepsLongKubectlApplyMessage() throws Exception {
        String detail = "failed to deploy kubernetes stack: failed to execute kubectl apply command: "
                + "failed to apply resources: failed to apply resource: "
                + "failed to create Deployment test-nginx/nginx-demo: "
                + "namespaces \"test-nginx\" not found";
        assertTrue(detail.length() > 200);
        String json = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("message", detail));
        String extracted = PortainerClient.extractErrorDetail(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(detail, extracted);
        assertFalse(extracted.endsWith("…"));
    }

    @Test
    public void extractErrorDetail_truncatesOnlyVeryLongBodies() {
        String detail = "x".repeat(PortainerClient.MAX_ERROR_DETAIL_CHARS + 50);
        String json = "{\"message\":\"" + detail + "\"}";
        String extracted = PortainerClient.extractErrorDetail(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(PortainerClient.MAX_ERROR_DETAIL_CHARS + 1, extracted.length());
        assertTrue(extracted.endsWith("…"));
        assertEquals(
                "x".repeat(PortainerClient.MAX_ERROR_DETAIL_CHARS),
                extracted.substring(0, PortainerClient.MAX_ERROR_DETAIL_CHARS));
    }

    @Test
    public void httpError_403WithDialTcp_isConnectivityNotPermission() {
        byte[] body = ("{\"message\":\"connecting to portainer.example:80: "
                        + "connecting to portainer.example:80: dial tcp 10.0.0.1:80: connectex\"}")
                .getBytes(StandardCharsets.UTF_8);
        IOException err = PortainerClient.httpError(403, body, URI.create("http://portainer.example:80/api/status"));
        String msg = err.getMessage();
        assertTrue(msg.contains("connectivity") || msg.contains("cannot connect"));
        assertTrue(msg.toLowerCase().contains("9000") || msg.toLowerCase().contains("9443"));
        assertFalse(msg.toLowerCase().contains("lacks permission"));
    }

    @Test
    public void httpError_403WithoutConnectivity_isPermission() {
        IOException err = PortainerClient.httpError(
                403, "{\"message\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(err.getMessage().contains("lacks permission"));
        assertTrue(err.getMessage().contains("Forbidden"));
    }

    @Test
    public void httpError_helmChartRepoTls_isLabeledNotGeneric500() {
        String body =
                "{\"message\":\"Unable to install a chart\",\"details\":\"Failed to load and validate chart for helm"
                        + " release installation: failed to find the helm chart at the path:"
                        + " https://gitlab.example/api/v4/projects/1/packages/helm/stable/nginx: looks like"
                        + " \\\"https://gitlab.example/api/v4/projects/1/packages/helm/stable\\\" is not a valid chart"
                        + " repository or cannot be reached: Get"
                        + " \\\"https://gitlab.example/api/v4/projects/1/packages/helm/stable/index.yaml\\\":"
                        + " tls: failed to verify certificate: x509: certificate signed by unknown authority\"}";
        IOException ex = PortainerClient.httpError(500, body.getBytes(StandardCharsets.UTF_8));
        String msg = ex.getMessage();
        assertTrue(msg.contains("Helm chart repository fetch failed"));
        assertTrue(msg.contains("TLS to chart repo from Portainer"));
        assertTrue(msg.contains("index.yaml"));
        assertTrue(msg.contains("unknown authority"));
        assertTrue(msg.contains("truststore") || msg.contains("CA"));
        assertFalse(msg.contains("Kubernetes cluster unreachable"));
    }

    @Test
    public void httpError_helmChartRepo404_highlightsHttpStatus() throws Exception {
        String detail =
                "looks like \"https://gitlab.example/packages/helm/stable\" is not a valid chart repository"
                        + " or cannot be reached: Get \"https://gitlab.example/packages/helm/stable/index.yaml\":"
                        + " failed to fetch: 404 Not Found";
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("message", detail));
        IOException ex = PortainerClient.httpError(500, body.getBytes(StandardCharsets.UTF_8));
        assertTrue(ex.getMessage().contains("HTTP 404 from chart repo"));
        assertTrue(ex.getMessage().contains("404"));
    }

    @Test
    public void httpError_helmChartRepo401_hintsCeCannotPassCreds() throws Exception {
        String detail =
                "looks like \"https://gitlab.example/api/v4/projects/1/packages/helm/stable\" is not a valid"
                        + " chart repository or cannot be reached: Get"
                        + " \"https://gitlab.example/api/v4/projects/1/packages/helm/stable/index.yaml\":"
                        + " failed to fetch: 401 Unauthorized";
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("message", detail));
        IOException ex = PortainerClient.httpError(500, body.getBytes(StandardCharsets.UTF_8));
        String msg = ex.getMessage();
        assertTrue(msg.contains("HTTP auth from chart repo"));
        assertTrue(msg.contains("no username/password"));
        assertTrue(msg.contains("cannot inject chart-repo credentials"));
        assertFalse(msg.toLowerCase().contains("deploy_token_secret"));
    }

    @Test
    public void rawErrorBodyForDebug_keepsJsonAsReturned() {
        String body =
                "{\n  \"message\": \"Unable to install a chart\",\n  \"details\": \"x509: unknown authority\"\n}";
        String dumped = PortainerClient.rawErrorBodyForDebug(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(dumped.contains("\"message\": \"Unable to install a chart\""));
        assertTrue(dumped.contains("x509: unknown authority"));
        assertFalse(dumped.contains("Helm chart repository fetch failed"));
        assertFalse(dumped.contains("Hint:"));
    }

    @Test
    public void rawErrorBodyForDebug_emptyAndHtml() {
        assertEquals("(empty)", PortainerClient.rawErrorBodyForDebug(new byte[0]));
        assertTrue(PortainerClient.rawErrorBodyForDebug("<html>nope</html>".getBytes(StandardCharsets.UTF_8))
                .startsWith("(HTML body"));
    }

    @Test
    public void extractInnermostGetFailure_takesLastGetClause() {
        String detail =
                "outer: Get \"https://a.example/index.yaml\": first; Get \"https://b.example/index.yaml\":"
                        + " tls: failed to verify certificate: x509: certificate signed by unknown authority";
        assertEquals(
                "Get \"https://b.example/index.yaml\": tls: failed to verify certificate: x509: certificate"
                        + " signed by unknown authority",
                PortainerClient.extractInnermostGetFailure(detail));
    }

    @Test
    public void httpError_imagePull_isLabeled() {
        IOException err = PortainerClient.httpError(
                500,
                "{\"message\":\"Error pulling image: pull access denied for example/app\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(err.getMessage().contains("image pull failed"));
        assertTrue(err.getMessage().toLowerCase().contains("pull access denied"));
    }

    @Test
    public void createStandalone_mapsImagePullMessage() {
        createCode.set(500);
        createBody.set("{\"message\":\"failed to pull image nginx:missing\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
        PortainerClient.StackFromGitRequest req = new PortainerClient.StackFromGitRequest(
                "myapp",
                "https://gitlab.example/group/stack.git",
                "docker-compose.yml",
                null,
                null,
                null,
                Collections.emptyList());
        try {
            client.createStandaloneStackFromRepository(base, "api-key", 1, req);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("image pull failed"));
            assertFalse(e.getMessage().contains("api-key"));
        }
        }
    }

    @Test
    public void mapTransportError_timeoutUnknownHostAndConnect() {
        URI uri = URI.create("http://portainer.example:9000/api/status");
        IOException timeout = PortainerClient.mapTransportError(
                uri, new java.net.http.HttpTimeoutException("timed out"));
        assertTrue(timeout.getMessage().toLowerCase().contains("timed out")
                || timeout.getMessage().toLowerCase().contains("timeout"));

        IOException unknown = PortainerClient.mapTransportError(
                uri, new IOException("wrap", new java.net.UnknownHostException("portainer.example")));
        assertTrue(unknown.getMessage().contains("portainer.example"));

        IOException connect = PortainerClient.mapTransportError(
                uri, new IOException("wrap", new java.net.ConnectException("Connection refused")));
        assertTrue(connect.getMessage().toLowerCase().contains("connect")
                || connect.getMessage().toLowerCase().contains("network"));

        IOException generic = PortainerClient.mapTransportError(uri, new IOException("boom"));
        assertEquals("boom", generic.getMessage());
    }

    @Test
    public void errorHelperStatics_connectivitySanitizeAndHtml() {
        assertTrue(PortainerClient.isConnectivityMessage("dial tcp 10.0.0.1:80: connectex"));
        assertFalse(PortainerClient.isConnectivityMessage("Forbidden"));
        assertEquals("safe", PortainerClient.sanitizeErrorDetail("safe"));
        assertTrue(PortainerClient.looksLikeHtml("<!DOCTYPE html><html>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(PortainerClient.looksLikeHtml("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("a — b", PortainerClient.combineErrorFields("a", "b", null));
        assertEquals("only", PortainerClient.combineErrorFields(null, null, "only"));
    }

    @Test
    public void parseDockerConfigSummary_readsLabels() throws Exception {
        JsonNode node = MAPPER.readTree(
                "{\"ID\":\"cfg1\",\"Spec\":{\"Name\":\"app-07d8fcbc\",\"Labels\":"
                        + "{\"jenkins.portainer.config/base\":\"app\",\"jenkins.portainer.config/hash\":\"07d8fcbc\"}}}");
        PortainerClient.DockerConfigSummary summary = PortainerClient.parseDockerConfigSummary(node);
        assertEquals("cfg1", summary.id);
        assertEquals("app-07d8fcbc", summary.name);
        assertEquals("app", summary.labels.get("jenkins.portainer.config/base"));
        assertEquals("07d8fcbc", summary.labels.get("jenkins.portainer.config/hash"));
    }

    @Test
    public void probeAccess_lowercaseVersionAndEndpoints403() throws Exception {
        statusBody.set("{\"version\":\"2.40.0\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            PortainerClient.ProbeDetails details = client.probeAccess(base, "key");
            assertEquals("Portainer v2.40.0", details.primaryLabel());
        }

        statusBody.set("{\"Version\":\"2.39.3\"}");
        endpointsCode.set(403);
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.probeAccess(base, "key");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("lacks permission")
                        || e.getMessage().contains("403"));
                assertTrue(e.getMessage().contains("/api/endpoints"));
            }
        }
    }

    @Test
    public void probeAccess_statusOkWithoutVersionLabel() throws Exception {
        statusBody.set("{}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            assertEquals("Portainer status OK", client.probeAccess(base, "key").primaryLabel());
        }
    }

    @Test
    public void findStackIdByName_blankAndAlternateIdFields() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            assertEquals(-1, client.findStackIdByName(base, "k", " ", 1));
            assertEquals(-1, client.findStackIdByName(base, "k", null, 1));
        }

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/stacks", exchange -> {
            capture(exchange);
            respond(
                    exchange,
                    200,
                    "[{\"ID\":11,\"Name\":\"alt\",\"EndpointID\":5},"
                            + "{\"Id\":12,\"Name\":\"alt\",\"EndpointId\":9}]");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            assertEquals(11, client.findStackIdByName(base, "k", "alt", 5));
            assertEquals(12, client.findStackIdByName(base, "k", "alt", 9));
            assertEquals(-1, client.findStackIdByName(base, "k", "alt", 1));
        }
    }

    @Test
    public void createDockerNamedResource_blankNameAnd409Conflict() {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.createDockerConfig(
                        base, "k", 1, new PortainerClient.DockerConfigCreateRequest(" ", new byte[0], null));
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("name is required"));
            }
        }

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/endpoints/1/docker/configs/create", exchange -> {
            capture(exchange);
            respond(exchange, 409, "{\"message\":\"name conflict\"}");
        });
        server.createContext("/api/endpoints/1/docker/secrets/create", exchange -> {
            capture(exchange);
            respond(exchange, 409, "{\"message\":\"name conflict\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.createDockerConfig(
                        base,
                        "k",
                        1,
                        new PortainerClient.DockerConfigCreateRequest("dup", "x".getBytes(StandardCharsets.UTF_8), Map.of()));
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("already exists"));
                assertTrue(e.getMessage().contains("dup"));
            }
            try {
                Map<String, String> labels = new java.util.LinkedHashMap<>();
                labels.put(" ", "skip");
                labels.put("k", null);
                client.createDockerSecret(
                        base,
                        "k",
                        1,
                        new PortainerClient.DockerSecretCreateRequest(
                                "dup-sec", "y".getBytes(StandardCharsets.UTF_8), labels));
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("already exists"));
                assertTrue(e.getMessage().contains("secret"));
            }
        }
    }

    @Test
    public void createDockerConfig_nullDataAndEmptyLabels() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            client.createDockerConfig(
                    base, "k", 1, new PortainerClient.DockerConfigCreateRequest("plain", null, Map.of()));
            JsonNode body = MAPPER.readTree(lastBody.get());
            assertEquals("plain", body.path("Name").asText());
            assertEquals("", body.path("Data").asText());
            assertFalse(body.has("Labels"));
        }
    }

    @Test
    public void removeDockerConfigAndSecret_blankId() {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.removeDockerConfig(base, "k", 1, " ");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("config id"));
            }
            try {
                client.removeDockerSecret(base, "k", 1, null);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("secret id"));
            }
        }
    }

    @Test
    public void parseDockerConfigSummary_edgeCases() throws Exception {
        assertEquals(null, PortainerClient.parseDockerConfigSummary(null));
        assertEquals(null, PortainerClient.parseDockerConfigSummary(MAPPER.nullNode()));
        assertEquals(null, PortainerClient.parseDockerConfigSummary(MAPPER.readTree("{\"ID\":\"x\"}")));
        JsonNode flat = MAPPER.readTree("{\"Id\":\"i1\",\"Name\":\"n1\"}");
        PortainerClient.DockerConfigSummary s = PortainerClient.parseDockerConfigSummary(flat);
        assertEquals("i1", s.id);
        assertEquals("n1", s.name);
        assertTrue(s.labels.isEmpty());
    }

    @Test
    public void isHttpStatus_andEnsureNamespaceBlank() {
        assertFalse(PortainerClient.isHttpStatus(null, 404));
        assertFalse(PortainerClient.isHttpStatus(new IOException(), 404));
        assertTrue(PortainerClient.isHttpStatus(new IOException("HTTP 404 - gone"), 404));
        assertTrue(PortainerClient.isHttpStatus(new IOException("wrap HTTP 409 - conflict"), 409));
        assertFalse(PortainerClient.isHttpStatus(new IOException("HTTP 40"), 404));

        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.ensureNamespace(base, "k", 1, " ");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("namespace is required"));
            }
        }
    }

    @Test
    public void httpJson_emptyBodyAndHtmlErrorAndBuildLogVerbose() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/endpoints/1/kubernetes/version", exchange -> {
            capture(exchange);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/endpoints/1/kubernetes/helm", exchange -> {
            capture(exchange);
            respond(exchange, 500, "<!DOCTYPE html><html>ui</html>");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        hudson.util.StreamTaskListener listener =
                new hudson.util.StreamTaskListener(buf, StandardCharsets.UTF_8);
        PortainerBuildLogger log = new PortainerBuildLogger(
                java.util.logging.Logger.getLogger("PortainerClientTest"), listener, true);

        try (PortainerClient client = new PortainerClient(2000, 2000, log)) {
            JsonNode empty = client.probeKubernetesVersion(base, "k", 1);
            assertTrue(empty.isObject());
            assertTrue(empty.isEmpty());

            try {
                client.listHelmReleases(base, "k", 1, null);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("html"));
            }
        }
        String console = buf.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("kubernetes") || console.toLowerCase().contains("http"));
    }

    @Test
    public void helmReleaseExists_andUninstallBlankName() throws Exception {
        helmListBody.set("{\"not\":\"array\"}");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            assertFalse(client.helmReleaseExists(base, "k", 1, "nginx", "default"));
        }
        helmListBody.set(
                "[{\"Name\":\"nginx\",\"Namespace\":\"other\"},{\"name\":\"web\",\"namespace\":\"\"}]");
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            assertFalse(client.helmReleaseExists(base, "k", 1, "nginx", "default"));
            assertTrue(client.helmReleaseExists(base, "k", 1, "web", "apps"));
            assertTrue(client.helmReleaseExists(base, "k", 1, "web", " "));
            try {
                client.uninstallHelmRelease(base, "k", 1, " ", "default");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().toLowerCase().contains("release name"));
            }
        }
    }

    @Test
    public void updateKubernetesStackGit_andResolveSwarmIdVariants() throws Exception {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            client.updateKubernetesStackGit(
                    base,
                    "k",
                    7,
                    1,
                    new PortainerClient.KubernetesGitUpdateRequest("refs/heads/main", "u", null));
            assertEquals("PUT", lastMethod.get());
            JsonNode body = MAPPER.readTree(lastBody.get());
            assertEquals("refs/heads/main", body.path("RepositoryReferenceName").asText());
            assertTrue(body.path("RepositoryAuthentication").asBoolean());
            assertEquals("u", body.path("RepositoryUsername").asText());
            assertFalse(body.has("RepositoryPassword"));
            assertFalse(body.has("TLSSkipVerify"));
        }

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> swarmBody = new AtomicReference<>("{\"Id\":\"swarm-lower\"}");
        server.createContext("/api/endpoints", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path != null && path.contains("/docker/swarm")) {
                respond(exchange, 200, swarmBody.get());
                return;
            }
            respond(exchange, 200, "[]");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            assertEquals("swarm-lower", client.resolveSwarmId(base, "k", 1));
            swarmBody.set("{}");
            try {
                client.resolveSwarmId(base, "k", 1);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("Swarm ID"));
            }
        }
    }

    @Test
    public void httpError_statusCodesAndIsHttpStatusHelpers() {
        assertTrue(PortainerClient.httpError(404, new byte[0]).getMessage().contains("404"));
        assertTrue(PortainerClient.httpError(409, "{\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8))
                .getMessage()
                .contains("409"));
        assertTrue(PortainerClient.classifyChartRepoFetchFailure("connection refused").contains("network"));
        assertEquals("chart repo unreachable", PortainerClient.classifyChartRepoFetchFailure("weird"));
        assertEquals("", PortainerClient.extractInnermostGetFailure(null));
        assertEquals("", PortainerClient.helmChartRepositoryHint("already Hint: present"));
    }

    @Test
    public void getStackEnv_rejectsNegativeId() {
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.getStackEnv(base, "k", -1);
                fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("stackId"));
            }
        }
    }

    @Test
    public void listDockerConfigs_nonArrayRejected() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/endpoints/1/docker/configs", exchange -> {
            capture(exchange);
            respond(exchange, 200, "{\"not\":\"array\"}");
        });
        server.createContext("/api/endpoints/1/docker/secrets", exchange -> {
            capture(exchange);
            respond(exchange, 200, "{\"not\":\"array\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (PortainerClient client = new PortainerClient(2000, 2000)) {
            try {
                client.listDockerConfigs(base, "k", 1);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("array"));
            }
            try {
                client.listDockerSecrets(base, "k", 1);
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("array"));
            }
        }
    }

    private void capture(HttpExchange exchange) throws IOException {
        lastApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
        lastQuery.set(exchange.getRequestURI().getRawQuery());
        byte[] raw = exchange.getRequestBody().readAllBytes();
        lastBody.set(raw.length == 0 ? "" : new String(raw, StandardCharsets.UTF_8));
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
