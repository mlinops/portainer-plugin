package io.jenkins.plugins.portainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ProxyConfiguration;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal Portainer HTTP client (API ≥ 2.39.3). Auth: {@code X-API-Key}.
 * One {@link HttpClient} per instance; close after the step / probe finishes.
 */
final class PortainerClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(PortainerClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final PortainerBuildLogger buildLog;
    private final HttpClient http;

    PortainerClient(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, null);
    }

    PortainerClient(int connectTimeoutMs, int readTimeoutMs, PortainerBuildLogger buildLog) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.buildLog = buildLog;
        this.http = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void close() {
        http.close();
    }

    /**
     * Connectivity + permissions probe: {@code GET /api/status}, then {@code GET /api/endpoints}.
     * Falls back to endpoints-only when status is missing (404), not on auth/connectivity failures.
     *
     * @return primary label for UI (version); permissions success is implied when this returns
     */
    ProbeDetails probeAccess(String baseUrl, String apiKey) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String versionLabel;
        try {
            JsonNode status = httpJson("GET", base + "/api/status", apiKey, null, null);
            String version = text(status, "Version");
            if (version.isBlank()) {
                version = text(status, "version");
            }
            versionLabel = !version.isBlank() ? "Portainer v" + version : "Portainer status OK";
        } catch (IOException statusEx) {
            String msg = statusEx.getMessage() == null ? "" : statusEx.getMessage();
            if (isAuthFailureMessage(msg) || isConnectivityMessage(msg)) {
                throw statusEx;
            }
            LOGGER.log(Level.FINE, "GET /api/status failed, trying /api/endpoints: {0}", statusEx.toString());
            httpJson("GET", base + "/api/endpoints", apiKey, null, null);
            return new ProbeDetails("Portainer endpoints reachable");
        }

        try {
            httpJson("GET", base + "/api/endpoints", apiKey, null, null);
            return new ProbeDetails(versionLabel);
        } catch (IOException permEx) {
            String msg = permEx.getMessage() == null ? "" : permEx.getMessage();
            if (isConnectivityMessage(msg)
                    || msg.contains("401")
                    || msg.toLowerCase(Locale.ROOT).contains("invalid or missing")) {
                throw permEx;
            }
            if (msg.contains("403") || msg.toLowerCase(Locale.ROOT).contains("lacks permission")) {
                throw new IOException(
                        "Portainer reachable but API key lacks permission to list endpoints (GET /api/endpoints)"
                                + detailSuffix(msg),
                        permEx);
            }
            throw permEx;
        }
    }

    /** Result of {@link #probeAccess}: primary UI label only (no environment count). */
    static final class ProbeDetails {
        private final String primaryLabel;

        ProbeDetails(String primaryLabel) {
            this.primaryLabel = primaryLabel;
        }

        String primaryLabel() {
            return primaryLabel;
        }
    }

    /**
     * {@code GET /api/endpoints/{endpointId}} — verifies the environment exists and is readable.
     *
     * @return endpoint JSON (Id, Name, …)
     */
    JsonNode getEndpoint(String baseUrl, String apiKey, int endpointId) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        try {
            return httpJson("GET", base + "/api/endpoints/" + endpointId, apiKey, null, null);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404")) {
                throw new IOException(
                        "Portainer endpoint ID " + endpointId + " was not found or is not available"
                                + detailSuffix(msg),
                        e);
            }
            throw e;
        }
    }

    /**
     * Create a Compose (standalone) stack from a Git repository.
     * {@code POST /api/stacks/create/standalone/repository?endpointId=}
     */
    JsonNode createStandaloneStackFromRepository(
            String baseUrl,
            String apiKey,
            int endpointId,
            StackFromGitRequest request) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/create/standalone/repository?endpointId=" + endpointId;
        ObjectNode body = stackFromGitBody(request, null);
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * Resolve Docker Swarm cluster ID via Docker proxy, then create a Swarm stack from Git.
     * {@code GET /api/endpoints/{id}/docker/swarm} →
     * {@code POST /api/stacks/create/swarm/repository?endpointId=}
     */
    JsonNode createSwarmStackFromRepository(
            String baseUrl,
            String apiKey,
            int endpointId,
            StackFromGitRequest request) throws IOException {
        String swarmId = resolveSwarmId(baseUrl, apiKey, endpointId);
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/create/swarm/repository?endpointId=" + endpointId;
        ObjectNode body = stackFromGitBody(request, swarmId);
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * {@code GET /api/endpoints/{endpointId}/docker/swarm} → swarm {@code ID}.
     */
    String resolveSwarmId(String baseUrl, String apiKey, int endpointId) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        JsonNode swarm = httpJson(
                "GET", base + "/api/endpoints/" + endpointId + "/docker/swarm", apiKey, null, null);
        String id = text(swarm, "ID");
        if (id.isBlank()) {
            id = text(swarm, "Id");
        }
        if (id.isBlank()) {
            throw new IOException("Portainer Docker swarm proxy did not return Swarm ID for endpoint " + endpointId);
        }
        return id;
    }

    /**
     * Find stack id by {@code Name} + {@code EndpointId} via {@code GET /api/stacks}.
     *
     * @return stack id, or {@code -1} if not found
     */
    int findStackIdByName(String baseUrl, String apiKey, String stackName, int endpointId) throws IOException {
        if (stackName == null || stackName.isBlank()) {
            return -1;
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        JsonNode stacks = httpJson("GET", base + "/api/stacks", apiKey, null, "find by name");
        if (!stacks.isArray()) {
            throw new IOException("Portainer GET /api/stacks did not return an array");
        }
        String want = stackName.trim();
        for (JsonNode stack : stacks) {
            String name = text(stack, "Name");
            if (!want.equals(name)) {
                continue;
            }
            int ep = stack.path("EndpointId").asInt(Integer.MIN_VALUE);
            if (ep == Integer.MIN_VALUE) {
                ep = stack.path("EndpointID").asInt(Integer.MIN_VALUE);
            }
            if (ep == endpointId) {
                int id = stack.path("Id").asInt(-1);
                if (id < 0) {
                    id = stack.path("ID").asInt(-1);
                }
                if (id >= 0) {
                    return id;
                }
            }
        }
        return -1;
    }

    /**
     * Load stack Env via {@code GET /api/stacks/{id}}. Missing or empty Env → empty list.
     */
    List<EnvPair> getStackEnv(String baseUrl, String apiKey, int stackId) throws IOException {
        if (stackId < 0) {
            throw new IllegalArgumentException("stackId must be >= 0");
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        JsonNode stack = httpJson("GET", base + "/api/stacks/" + stackId, apiKey, null, "get stack env");
        return parseStackEnv(stack);
    }

    /**
     * Parse Portainer stack {@code Env} array ({@code name}/{@code value} or {@code Name}/{@code Value}).
     */
    static List<EnvPair> parseStackEnv(JsonNode stack) {
        if (stack == null || stack.isMissingNode() || stack.isNull()) {
            return List.of();
        }
        JsonNode env = stack.get("Env");
        if (env == null || env.isMissingNode() || env.isNull() || !env.isArray()) {
            return List.of();
        }
        List<EnvPair> out = new ArrayList<>();
        for (JsonNode n : env) {
            if (n == null || n.isNull() || !n.isObject()) {
                continue;
            }
            String name = firstNonBlank(text(n, "name"), text(n, "Name"));
            if (name.isBlank()) {
                continue;
            }
            String value = firstNonBlank(text(n, "value"), text(n, "Value"));
            out.add(new EnvPair(name, value));
        }
        return out;
    }

    /**
     * Git redeploy: {@code PUT /api/stacks/{id}/git/redeploy?endpointId=}
     */
    JsonNode gitRedeploy(
            String baseUrl,
            String apiKey,
            int stackId,
            int endpointId,
            GitRedeployRequest request) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/" + stackId + "/git/redeploy?endpointId=" + endpointId;
        ObjectNode body = MAPPER.createObjectNode();
        if (request.env != null && !request.env.isEmpty()) {
            body.set("Env", toEnvArray(request.env));
        }
        if (request.prune) {
            body.put("Prune", true);
        }
        if (request.repullImageAndRedeploy) {
            body.put("RepullImageAndRedeploy", true);
        }
        if (request.repositoryReferenceName != null && !request.repositoryReferenceName.isBlank()) {
            body.put("RepositoryReferenceName", request.repositoryReferenceName.trim());
        }
        if (request.gitUsername != null || request.gitPassword != null) {
            body.put("RepositoryAuthentication", true);
            if (request.gitUsername != null) {
                body.put("RepositoryUsername", request.gitUsername);
            }
            if (request.gitPassword != null) {
                body.put("RepositoryPassword", request.gitPassword);
            }
        }
        // Never send TLSSkipVerify
        return httpJson("PUT", url, apiKey, body, null);
    }

    /**
     * Create a Compose (standalone) stack from inline YAML.
     * {@code POST /api/stacks/create/standalone/string?endpointId=}
     */
    JsonNode createStandaloneStackFromString(
            String baseUrl,
            String apiKey,
            int endpointId,
            StackFromStringRequest request) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/create/standalone/string?endpointId=" + endpointId;
        ObjectNode body = stackFromStringBody(request, null);
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * Resolve Swarm ID, then create a Swarm stack from inline YAML.
     * {@code POST /api/stacks/create/swarm/string?endpointId=}
     */
    JsonNode createSwarmStackFromString(
            String baseUrl,
            String apiKey,
            int endpointId,
            StackFromStringRequest request) throws IOException {
        String swarmId = resolveSwarmId(baseUrl, apiKey, endpointId);
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/create/swarm/string?endpointId=" + endpointId;
        ObjectNode body = stackFromStringBody(request, swarmId);
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * Create a Kubernetes stack from inline manifest YAML.
     * {@code POST /api/stacks/create/kubernetes/string?endpointId=}
     * <p>
     * Portainer response is typically {@code {"Output":"…"}} (not a stack {@code Id}); callers may
     * {@link #findStackIdByName} afterward when the stack name is known.
     */
    JsonNode createKubernetesStackFromString(
            String baseUrl,
            String apiKey,
            int endpointId,
            KubernetesFromStringRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/create/kubernetes/string?endpointId=" + endpointId;
        ObjectNode body = MAPPER.createObjectNode();
        // Portainer K8s Validate() does not require StackName (only StackFileContent).
        if (request.stackName != null && !request.stackName.isBlank()) {
            body.put("StackName", request.stackName.trim());
        }
        body.put("StackFileContent", request.stackFileContent == null ? "" : request.stackFileContent);
        if (request.namespace != null && !request.namespace.isBlank()) {
            body.put("Namespace", request.namespace.trim());
        }
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * Create a Kubernetes stack from a Git repository.
     * {@code POST /api/stacks/create/kubernetes/repository?endpointId=}
     */
    JsonNode createKubernetesStackFromRepository(
            String baseUrl,
            String apiKey,
            int endpointId,
            KubernetesFromGitRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/create/kubernetes/repository?endpointId=" + endpointId;
        ObjectNode body = MAPPER.createObjectNode();
        if (request.stackName != null && !request.stackName.isBlank()) {
            body.put("StackName", request.stackName.trim());
        }
        body.put("RepositoryURL", request.repositoryUrl);
        body.put("ManifestFile", request.manifestFile == null ? "" : request.manifestFile.trim());
        if (request.namespace != null && !request.namespace.isBlank()) {
            body.put("Namespace", request.namespace.trim());
        }
        if (request.repositoryReferenceName != null && !request.repositoryReferenceName.isBlank()) {
            body.put("RepositoryReferenceName", request.repositoryReferenceName.trim());
        }
        if (request.gitUsername != null || request.gitPassword != null) {
            body.put("RepositoryAuthentication", true);
            if (request.gitUsername != null) {
                body.put("RepositoryUsername", request.gitUsername);
            }
            if (request.gitPassword != null) {
                body.put("RepositoryPassword", request.gitPassword);
            }
        }
        // Never send TLSSkipVerify
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * Update a file-based Kubernetes stack:
     * {@code PUT /api/stacks/{id}?endpointId=} with {@code StackFileContent}.
     */
    JsonNode updateKubernetesStackFileContent(
            String baseUrl,
            String apiKey,
            int stackId,
            int endpointId,
            KubernetesFileUpdateRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/" + stackId + "?endpointId=" + endpointId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("StackFileContent", request.stackFileContent == null ? "" : request.stackFileContent);
        if (request.stackName != null && !request.stackName.isBlank()) {
            body.put("StackName", request.stackName.trim());
        }
        try {
            return httpJson("PUT", url, apiKey, body, null);
        } catch (IOException e) {
            throw mapStackFileUpdateError(e);
        }
    }

    /**
     * Update a Git-backed Kubernetes stack:
     * {@code PUT /api/stacks/{id}?endpointId=} with repository reference / auth
     * (not Docker {@code /git/redeploy}).
     */
    JsonNode updateKubernetesStackGit(
            String baseUrl,
            String apiKey,
            int stackId,
            int endpointId,
            KubernetesGitUpdateRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/" + stackId + "?endpointId=" + endpointId;
        ObjectNode body = MAPPER.createObjectNode();
        if (request.repositoryReferenceName != null && !request.repositoryReferenceName.isBlank()) {
            body.put("RepositoryReferenceName", request.repositoryReferenceName.trim());
        }
        if (request.gitUsername != null || request.gitPassword != null) {
            body.put("RepositoryAuthentication", true);
            if (request.gitUsername != null) {
                body.put("RepositoryUsername", request.gitUsername);
            }
            if (request.gitPassword != null) {
                body.put("RepositoryPassword", request.gitPassword);
            }
        }
        // Never send TLSSkipVerify
        return httpJson("PUT", url, apiKey, body, null);
    }

    /**
     * Kubernetes API proxy health: {@code GET /api/endpoints/{id}/kubernetes/version}.
     * Fails when Portainer cannot reach the cluster (agent down, Public URL TLS mismatch, etc.).
     */
    JsonNode probeKubernetesVersion(String baseUrl, String apiKey, int endpointId) throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        return httpJson(
                "GET",
                base + "/api/endpoints/" + endpointId + "/kubernetes/version",
                apiKey,
                null,
                "kubernetes version");
    }

    /**
     * Ensure a Kubernetes namespace exists via Portainer:
     * {@code GET /api/kubernetes/{id}/namespaces/{name}} then
     * {@code POST /api/kubernetes/{id}/namespaces} with {@code {"Name":"…"}} when missing.
     * Treats HTTP 409 (AlreadyExists) as success for concurrent creates.
     *
     * @return {@code existed}, {@code created}, or {@code already-exists}
     */
    String ensureNamespace(String baseUrl, String apiKey, int endpointId, String namespace)
            throws IOException {
        if (namespace == null || namespace.isBlank()) {
            throw new IOException("Namespace is required to ensure a Kubernetes namespace.");
        }
        String ns = namespace.trim();
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String encoded = java.net.URLEncoder.encode(ns, StandardCharsets.UTF_8).replace("+", "%20");
        String getUrl = base + "/api/kubernetes/" + endpointId + "/namespaces/" + encoded
                + "?withResourceQuota=false";
        try {
            httpJson("GET", getUrl, apiKey, null, "get namespace");
            return "existed";
        } catch (IOException getEx) {
            if (!isHttpStatus(getEx, 404)) {
                throw mapEnsureNamespaceError(getEx, ns);
            }
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Name", ns);
        String createUrl = base + "/api/kubernetes/" + endpointId + "/namespaces";
        try {
            httpJson("POST", createUrl, apiKey, body, "create namespace");
            return "created";
        } catch (IOException createEx) {
            if (isHttpStatus(createEx, 409)) {
                return "already-exists";
            }
            throw mapEnsureNamespaceError(createEx, ns);
        }
    }

    private static IOException mapEnsureNamespaceError(IOException e, String namespace) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (isHttpStatus(e, 403)
                || lower.contains("lacks permission")
                || lower.contains("forbidden")
                || lower.contains("permission denied")) {
            return new IOException(
                    "Cannot ensure namespace \""
                            + namespace
                            + "\": Portainer API key lacks permission (RBAC)"
                            + detailSuffix(msg),
                    e);
        }
        return new IOException(
                "Cannot ensure namespace \"" + namespace + "\"" + detailSuffix(msg), e);
    }

    /** True when the IOException message starts with or contains {@code HTTP <code>}. */
    static boolean isHttpStatus(IOException e, int code) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String needle = "HTTP " + code;
        return msg.startsWith(needle) || msg.contains(needle + " ") || msg.contains(needle + " -");
    }

    /**
     * List Helm releases: {@code GET /api/endpoints/{id}/kubernetes/helm?namespace=}
     */
    JsonNode listHelmReleases(String baseUrl, String apiKey, int endpointId, String namespace)
            throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        StringBuilder url = new StringBuilder(base)
                .append("/api/endpoints/")
                .append(endpointId)
                .append("/kubernetes/helm");
        if (namespace != null && !namespace.isBlank()) {
            url.append("?namespace=").append(java.net.URLEncoder.encode(namespace.trim(), StandardCharsets.UTF_8));
        }
        return httpJson("GET", url.toString(), apiKey, null, "list helm");
    }

    /**
     * Whether a Helm release with the given name exists in {@code namespace}
     * (empty namespace matches any when Portainer returns it).
     */
    boolean helmReleaseExists(
            String baseUrl, String apiKey, int endpointId, String releaseName, String namespace)
            throws IOException {
        JsonNode releases = listHelmReleases(baseUrl, apiKey, endpointId, namespace);
        if (!releases.isArray()) {
            return false;
        }
        String want = releaseName == null ? "" : releaseName.trim();
        String ns = namespace == null ? "" : namespace.trim();
        for (JsonNode r : releases) {
            String name = firstNonBlank(text(r, "Name"), text(r, "name"));
            if (!want.equals(name)) {
                continue;
            }
            if (ns.isBlank()) {
                return true;
            }
            String releaseNs = firstNonBlank(text(r, "Namespace"), text(r, "namespace"));
            if (ns.equals(releaseNs) || releaseNs.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Install or upgrade Helm chart: {@code POST /api/endpoints/{id}/kubernetes/helm}
     * <p>
     * Portainer (≥ 2.39) has no separate upgrade HTTP route; libhelm install is install-or-upgrade.
     * Callers may optionally uninstall first ({@code forceReinstall}) for a destructive reinstall.
     */
    JsonNode installHelmChart(
            String baseUrl, String apiKey, int endpointId, HelmInstallRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/endpoints/" + endpointId + "/kubernetes/helm";
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", request.releaseName);
        body.put("chart", request.chart);
        body.put("repo", request.repo);
        if (request.namespace != null && !request.namespace.isBlank()) {
            body.put("namespace", request.namespace.trim());
        }
        if (request.version != null && !request.version.isBlank()) {
            body.put("version", request.version.trim());
        }
        if (request.values != null && !request.values.isBlank()) {
            body.put("values", request.values);
        }
        if (request.atomic) {
            body.put("atomic", true);
        }
        return httpJson("POST", url, apiKey, body, null);
    }

    /**
     * Uninstall Helm release: {@code DELETE /api/endpoints/{id}/kubernetes/helm/{release}?namespace=}
     */
    JsonNode uninstallHelmRelease(
            String baseUrl, String apiKey, int endpointId, String releaseName, String namespace)
            throws IOException {
        if (releaseName == null || releaseName.isBlank()) {
            throw new IOException("Helm release name is required for uninstall.");
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String encoded = java.net.URLEncoder.encode(releaseName.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        StringBuilder url = new StringBuilder(base)
                .append("/api/endpoints/")
                .append(endpointId)
                .append("/kubernetes/helm/")
                .append(encoded);
        if (namespace != null && !namespace.isBlank()) {
            url.append("?namespace=").append(java.net.URLEncoder.encode(namespace.trim(), StandardCharsets.UTF_8));
        }
        return httpJson("DELETE", url.toString(), apiKey, null, "uninstall helm");
    }

    /**
     * List Docker Swarm configs via Portainer proxy.
     * {@code GET /api/endpoints/{endpointId}/docker/configs}
     */
    List<DockerConfigSummary> listDockerConfigs(String baseUrl, String apiKey, int endpointId)
            throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        JsonNode configs = httpJson(
                "GET", base + "/api/endpoints/" + endpointId + "/docker/configs", apiKey, null, "list configs");
        if (!configs.isArray()) {
            throw new IOException("Portainer GET /docker/configs did not return an array");
        }
        List<DockerConfigSummary> out = new ArrayList<>();
        for (JsonNode node : configs) {
            DockerConfigSummary summary = parseDockerConfigSummary(node);
            if (summary != null) {
                out.add(summary);
            }
        }
        return out;
    }

    /**
     * Create Docker Swarm config.
     * {@code POST /api/endpoints/{endpointId}/docker/configs/create}
     */
    JsonNode createDockerConfig(
            String baseUrl, String apiKey, int endpointId, DockerConfigCreateRequest request)
            throws IOException {
        Objects.requireNonNull(request, "request");
        if (request.name == null || request.name.isBlank()) {
            throw new IOException("Docker config name is required.");
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Name", request.name.trim());
        body.put("Data", Base64.getEncoder().encodeToString(request.data == null ? new byte[0] : request.data));
        if (request.labels != null && !request.labels.isEmpty()) {
            ObjectNode labels = MAPPER.createObjectNode();
            for (Map.Entry<String, String> e : request.labels.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank()) {
                    labels.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
            body.set("Labels", labels);
        }
        try {
            return httpJson(
                    "POST",
                    base + "/api/endpoints/" + endpointId + "/docker/configs/create",
                    apiKey,
                    body,
                    "create config");
        } catch (IOException e) {
            throw mapDockerConfigCreateError(e, request.name);
        }
    }

    /**
     * Remove Docker Swarm config.
     * {@code DELETE /api/endpoints/{endpointId}/docker/configs/{id}}
     */
    void removeDockerConfig(String baseUrl, String apiKey, int endpointId, String configId)
            throws IOException {
        if (configId == null || configId.isBlank()) {
            throw new IOException("Docker config ID is required for delete.");
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        httpJson(
                "DELETE",
                base + "/api/endpoints/" + endpointId + "/docker/configs/" + configId.trim(),
                apiKey,
                null,
                "remove config");
    }

    /**
     * List Docker Swarm secrets via Portainer proxy.
     * {@code GET /api/endpoints/{endpointId}/docker/secrets}
     */
    List<DockerConfigSummary> listDockerSecrets(String baseUrl, String apiKey, int endpointId)
            throws IOException {
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        JsonNode secrets = httpJson(
                "GET", base + "/api/endpoints/" + endpointId + "/docker/secrets", apiKey, null, "list secrets");
        if (!secrets.isArray()) {
            throw new IOException("Portainer GET /docker/secrets did not return an array");
        }
        List<DockerConfigSummary> out = new ArrayList<>();
        for (JsonNode node : secrets) {
            DockerConfigSummary summary = parseDockerConfigSummary(node);
            if (summary != null) {
                out.add(summary);
            }
        }
        return out;
    }

    /**
     * Create Docker Swarm secret.
     * {@code POST /api/endpoints/{endpointId}/docker/secrets/create}
     */
    JsonNode createDockerSecret(
            String baseUrl, String apiKey, int endpointId, DockerSecretCreateRequest request)
            throws IOException {
        Objects.requireNonNull(request, "request");
        if (request.name == null || request.name.isBlank()) {
            throw new IOException("Docker secret name is required.");
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Name", request.name.trim());
        body.put("Data", Base64.getEncoder().encodeToString(request.data == null ? new byte[0] : request.data));
        if (request.labels != null && !request.labels.isEmpty()) {
            ObjectNode labels = MAPPER.createObjectNode();
            for (Map.Entry<String, String> e : request.labels.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank()) {
                    labels.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
            body.set("Labels", labels);
        }
        try {
            return httpJson(
                    "POST",
                    base + "/api/endpoints/" + endpointId + "/docker/secrets/create",
                    apiKey,
                    body,
                    "create secret");
        } catch (IOException e) {
            throw mapDockerSecretCreateError(e, request.name);
        }
    }

    /**
     * Remove Docker Swarm secret.
     * {@code DELETE /api/endpoints/{endpointId}/docker/secrets/{id}}
     */
    void removeDockerSecret(String baseUrl, String apiKey, int endpointId, String secretId)
            throws IOException {
        if (secretId == null || secretId.isBlank()) {
            throw new IOException("Docker secret ID is required for delete.");
        }
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        httpJson(
                "DELETE",
                base + "/api/endpoints/" + endpointId + "/docker/secrets/" + secretId.trim(),
                apiKey,
                null,
                "remove secret");
    }

    static DockerConfigSummary parseDockerConfigSummary(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String id = firstNonBlank(text(node, "ID"), text(node, "Id"), text(node, "id"));
        JsonNode spec = node.path("Spec");
        if (spec.isMissingNode() || spec.isNull()) {
            spec = node;
        }
        String name = firstNonBlank(text(spec, "Name"), text(node, "Name"));
        if (name.isBlank()) {
            return null;
        }
        Map<String, String> labels = parseDockerConfigLabels(spec.path("Labels"));
        return new DockerConfigSummary(id, name, labels);
    }

    private static Map<String, String> parseDockerConfigLabels(JsonNode labelsNode) {
        if (labelsNode == null || labelsNode.isNull() || labelsNode.isMissingNode() || !labelsNode.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        labelsNode.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText("")));
        return Collections.unmodifiableMap(out);
    }

    private static IOException mapDockerConfigCreateError(IOException e, String configName) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("409")) {
            return new IOException(
                    "Docker config name \"" + configName + "\" already exists with different content. "
                            + "Content-hash naming should prevent this — check for a manual config or hash collision. "
                            + "Detail: " + truncateDetail(msg),
                    e);
        }
        return e;
    }

    private static IOException mapDockerSecretCreateError(IOException e, String secretName) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("409")) {
            return new IOException(
                    "Docker secret name \"" + secretName + "\" already exists with different content. "
                            + "Content-hash naming should prevent this — check for a manual secret or hash collision. "
                            + "Detail: " + truncateDetail(msg),
                    e);
        }
        return e;
    }

    /**
     * Update a file/string-based stack: {@code PUT /api/stacks/{id}?endpointId=}
     * with {@code StackFileContent} (not Git redeploy). Git-backed stacks may be rejected by Portainer.
     */
    JsonNode updateStackFileContent(
            String baseUrl,
            String apiKey,
            int stackId,
            int endpointId,
            StackFileUpdateRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = PortainerUrl.normalizeBaseUrl(baseUrl);
        String url = base + "/api/stacks/" + stackId + "?endpointId=" + endpointId;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("StackFileContent", request.stackFileContent == null ? "" : request.stackFileContent);
        if (request.env != null && !request.env.isEmpty()) {
            body.set("Env", toEnvArray(request.env));
        }
        if (request.prune) {
            body.put("Prune", true);
        }
        if (request.repullImageAndRedeploy) {
            body.put("RepullImageAndRedeploy", true);
        }
        try {
            return httpJson("PUT", url, apiKey, body, null);
        } catch (IOException e) {
            throw mapStackFileUpdateError(e);
        }
    }

    private static IOException mapStackFileUpdateError(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        boolean looksGitBacked = lower.contains("git")
                || lower.contains("file based")
                || lower.contains("file-based")
                || lower.contains("only for file")
                || (lower.contains("repository") && lower.contains("stack"));
        if (looksGitBacked) {
            return new IOException(
                    "Portainer rejected stack file update — this stack may be Git-backed. "
                            + "Manual YAML update applies only to stacks created from string/file content. "
                            + "Use Repository mode (git redeploy) for Git stacks, or recreate as Manual YAML. "
                            + "Detail: " + truncateDetail(msg),
                    e);
        }
        return e;
    }

    private ObjectNode stackFromStringBody(StackFromStringRequest request, String swarmId) {
        Objects.requireNonNull(request, "request");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Name", request.name);
        body.put("StackFileContent", request.stackFileContent == null ? "" : request.stackFileContent);
        if (request.env != null && !request.env.isEmpty()) {
            body.set("Env", toEnvArray(request.env));
        }
        if (swarmId != null && !swarmId.isBlank()) {
            body.put("SwarmID", swarmId);
        }
        return body;
    }

    private ObjectNode stackFromGitBody(StackFromGitRequest request, String swarmId) {
        Objects.requireNonNull(request, "request");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Name", request.name);
        body.put("RepositoryURL", request.repositoryUrl);
        if (request.composeFile != null && !request.composeFile.isBlank()) {
            body.put("ComposeFile", request.composeFile.trim());
        }
        if (request.repositoryReferenceName != null && !request.repositoryReferenceName.isBlank()) {
            body.put("RepositoryReferenceName", request.repositoryReferenceName.trim());
        }
        if (request.env != null && !request.env.isEmpty()) {
            body.set("Env", toEnvArray(request.env));
        }
        if (request.gitUsername != null || request.gitPassword != null) {
            body.put("RepositoryAuthentication", true);
            if (request.gitUsername != null) {
                body.put("RepositoryUsername", request.gitUsername);
            }
            if (request.gitPassword != null) {
                body.put("RepositoryPassword", request.gitPassword);
            }
        }
        if (swarmId != null && !swarmId.isBlank()) {
            body.put("SwarmID", swarmId);
        }
        // Never send TLSSkipVerify
        return body;
    }

    private ArrayNode toEnvArray(List<EnvPair> env) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (EnvPair p : env) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("name", p.name);
            n.put("value", p.value == null ? "" : p.value);
            arr.add(n);
        }
        return arr;
    }

    private JsonNode httpJson(String method, String apiUrl, String apiKey, JsonNode body, String debugNote)
            throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("Portainer API key is required.");
        }
        final URI uri;
        try {
            uri = URI.create(apiUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid Portainer API URL", e);
        }
        try {
            ConnectionTester.assertHostAllowed(apiUrl, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            ConnectionTester.assertUriHostAllowed(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1, readTimeoutMs)))
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json");

        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if ("GET".equals(m) || "DELETE".equals(m)) {
            req.method(m, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json");
            byte[] bytes = body == null ? "{}".getBytes(StandardCharsets.UTF_8) : MAPPER.writeValueAsBytes(body);
            req.method(m, HttpRequest.BodyPublishers.ofByteArray(bytes));
        }

        long startedNs = System.nanoTime();
        final HttpResponse<byte[]> response;
        try {
            response = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Portainer HTTP request interrupted", e);
        } catch (IOException e) {
            throw mapTransportError(uri, e);
        }

        long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
        String path = PortainerBuildLogger.safeRequestPath(uri);

        try {
            ConnectionTester.assertUriHostAllowed(response.uri());
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        int code = response.statusCode();
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (code < 200 || code >= 300) {
            if (buildLog != null) {
                buildLog.http(m, path, durationMs, debugNote);
                if (buildLog.isVerbose()) {
                    buildLog.debug("HTTP " + code + " raw response body: " + rawErrorBodyForDebug(bytes));
                }
            }
            throw httpError(code, bytes, uri);
        }
        if (buildLog != null) {
            buildLog.http(m, path, durationMs, debugNote);
        }
        if (bytes.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(bytes);
    }

    static IOException mapTransportError(URI uri, IOException e) {
        if (e instanceof HttpTimeoutException) {
            return new IOException(timeoutMessage(uri), e);
        }
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                String host = uri == null ? null : uri.getHost();
                return new IOException(
                        "Portainer host could not be resolved"
                                + (host == null || host.isBlank() ? "." : ": " + host + "."),
                        e);
            }
            if (t instanceof ConnectException
                    || isConnectivityMessage(t.getMessage() == null ? "" : t.getMessage())) {
                return new IOException(connectivityMessage(uri, t.getMessage()), e);
            }
            t = t.getCause();
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (isConnectivityMessage(msg)) {
            return new IOException(connectivityMessage(uri, msg), e);
        }
        return e;
    }

    static IOException httpError(int code, byte[] bodyBytes, URI uri) {
        if (looksLikeHtml(bodyBytes)) {
            return new IOException(
                    "HTTP "
                            + code
                            + " - response looks like HTML (Portainer UI page?), not the API."
                            + " Use the API base URL (typically http://host:9000 or https://host:9443),"
                            + " without a UI-only path.");
        }
        String detail = extractErrorDetail(bodyBytes);
        String suffix = detail.isBlank() ? "" : " - " + detail;

        if (isConnectivityMessage(detail)) {
            return new IOException(
                    "HTTP " + code + " - cannot connect to Portainer host/port (network/connectivity)"
                            + suffix
                            + portHintSuffix(uri, detail));
        }
        if (isImagePullFailure(detail)) {
            return new IOException("HTTP " + code + " - image pull failed" + suffix);
        }
        if (isHelmChartRepositoryFailure(detail)) {
            return new IOException(
                    "HTTP " + code + " - " + formatHelmChartRepositoryFailure(detail)
                            + helmChartRepositoryHint(detail));
        }
        if (isKubernetesConnectivityFailure(detail)) {
            return new IOException(
                    "HTTP " + code + " - Kubernetes cluster unreachable from Portainer" + suffix
                            + kubernetesConnectivityHint(detail));
        }
        if (code == 401) {
            return new IOException("HTTP 401 - invalid or missing Portainer API key" + suffix);
        }
        if (code == 403) {
            return new IOException("HTTP 403 - Portainer API key lacks permission" + suffix);
        }
        if (code == 404) {
            return new IOException(
                    "HTTP 404 - Portainer API path or resource not found (check URL / Portainer version ≥ 2.39.3)"
                            + suffix);
        }
        if (code == 409) {
            return new IOException("HTTP 409 - Portainer stack conflict (name may already exist)" + suffix);
        }
        return new IOException("HTTP " + code + suffix);
    }

    /** Package-visible for tests. */
    static IOException httpError(int code, byte[] bodyBytes) {
        return httpError(code, bodyBytes, null);
    }

    /**
     * Cap for Portainer/kubectl error text in build console and AbortException messages.
     * Short enough to avoid dumping megabyte HTML bodies; long enough for nested kubectl apply errors.
     */
    static final int MAX_ERROR_DETAIL_CHARS = 4000;

    /**
     * Verbose/debug dump of the HTTP error body as returned by Portainer — no message
     * classification or Helm hint rewriting. Secrets still redacted; very long bodies truncated.
     */
    static String rawErrorBodyForDebug(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "(empty)";
        }
        if (looksLikeHtml(bodyBytes)) {
            return truncateDetail("(HTML body, " + bodyBytes.length + " bytes)");
        }
        String raw = new String(bodyBytes, StandardCharsets.UTF_8);
        return truncateDetail(sanitizeErrorDetail(raw));
    }

    /**
     * Best-effort Portainer error text from response body (JSON {@code message}/{@code details}).
     * Never includes request secrets; truncates very long bodies.
     */
    static String extractErrorDetail(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "";
        }
        String raw = new String(bodyBytes, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            JsonNode errors = node.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode item : errors) {
                    if (item == null || item.isNull()) {
                        continue;
                    }
                    String part = item.asText("").trim();
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (joined.length() > 0) {
                        joined.append("; ");
                    }
                    joined.append(part);
                }
                if (joined.length() > 0) {
                    return truncateDetail(sanitizeErrorDetail(joined.toString()));
                }
            }
            String message = firstNonBlank(text(node, "message"), text(node, "Message"));
            String details = firstNonBlank(text(node, "details"), text(node, "Details"));
            String error = firstNonBlank(text(node, "error"), text(node, "Error"));
            String combined = combineErrorFields(message, details, error);
            if (!combined.isBlank()) {
                return truncateDetail(sanitizeErrorDetail(combined));
            }
        } catch (IOException ignored) {
            // fall through to raw body
        }
        return truncateDetail(sanitizeErrorDetail(raw.replaceAll("\\s+", " ")));
    }

    /** Merge Portainer JSON error fields without dropping {@code details} behind a generic {@code message}. */
    static String combineErrorFields(String message, String details, String error) {
        String msg = message == null ? "" : message.trim();
        String det = details == null ? "" : details.trim();
        String err = error == null ? "" : error.trim();
        if (!msg.isBlank() && !det.isBlank()) {
            if (msg.equals(det)) {
                return msg;
            }
            String msgLower = msg.toLowerCase(Locale.ROOT);
            String detLower = det.toLowerCase(Locale.ROOT);
            if (detLower.contains(msgLower) || msgLower.contains(detLower)) {
                return det.length() >= msg.length() ? det : msg;
            }
            return msg + " — " + det;
        }
        return firstNonBlank(msg, det, err);
    }

    /**
     * Strip likely secrets from API error text before console/JUL output.
     * Response bodies must not echo request API keys or Helm values.
     */
    static String sanitizeErrorDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        String s = detail;
        s = s.replaceAll(
                "(?i)(x-api-key|api[_-]?key|authorization|bearer|token|password|secret[_-]?id)\\s*[:=]\\s*\\S+",
                "$1=[redacted]");
        s = s.replaceAll("ptr_[A-Za-z0-9/+._=-]{8,}", "ptr_[redacted]");
        return s;
    }

    static boolean isConnectivityMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String d = message.toLowerCase(Locale.ROOT);
        return d.contains("dial tcp")
                || d.contains("connectex")
                || d.contains("connection refused")
                || d.contains("connect timed out")
                || d.contains("connection timed out")
                || d.contains("no route to host")
                || d.contains("network is unreachable")
                || d.contains("i/o timeout")
                || d.contains("failed to connect")
                || d.contains("cannot connect to portainer")
                || d.contains("cannot connect to portainer host")
                || (d.contains("connecting to") && (d.contains("tcp") || d.contains("dial") || d.contains("connect")));
    }

    /**
     * Portainer-side Kubernetes proxy / agent failures (not Jenkins → Portainer HTTP).
     * Package-visible for tests.
     */
    static boolean isKubernetesConnectivityFailure(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String d = detail.toLowerCase(Locale.ROOT);
        return d.contains("kubernetes cluster unreachable")
                || d.contains("cluster unreachable")
                || d.contains("failed to list helm releases")
                || d.contains("http response to https client")
                || d.contains("https response to http client")
                || (d.contains("kubernetes/version") && (d.contains("unreachable") || d.contains("connect")));
    }

    /**
     * Actionable operator hint when Portainer cannot proxy to Kubernetes. Never includes secrets.
     */
    static String kubernetesConnectivityHint(String detail) {
        if (!isKubernetesConnectivityFailure(detail)) {
            return "";
        }
        // Avoid doubling when callers wrap httpError() which already appended a hint.
        if (detail != null && detail.contains("Hint:")) {
            return "";
        }
        String d = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        // Portainer Helm/kube client hardcodes https://localhost:<https-bind>/api/endpoints/.../kubernetes
        // Custom certs for *.domain without SAN localhost fail hostname verification.
        if (d.contains("localhost")
                && (d.contains("not localhost")
                        || d.contains("certificate is valid for")
                        || d.contains("x509"))) {
            return " Hint: fix in Portainer (not Jenkins) — Helm self-calls https://localhost:<https-port>;"
                    + " add DNS:localhost (and IP:127.0.0.1) to the Portainer TLS certificate SAN,"
                    + " or use Helm outside Portainer. Public URL / Jenkins Portainer URL do not change this.";
        }
        StringBuilder hint = new StringBuilder(
                " Hint: fix in Portainer (not Jenkins) — open the Kubernetes environment in Portainer UI"
                        + " and confirm it is Up; confirm edge/agent tunnel or in-cluster access to the"
                        + " Kubernetes API.");
        if (d.contains("http response to https client") || d.contains("https response to http client")) {
            hint.append(
                    " TLS mismatch detected: Portainer may be using https on an HTTP-only port (or the reverse).");
        }
        return hint.toString();
    }

    /**
     * Portainer/Helm failed while fetching a chart repository ({@code index.yaml}), not the K8s proxy.
     * Helm often wraps TLS / 401 / 404 as "not a valid chart repository or cannot be reached".
     */
    static boolean isHelmChartRepositoryFailure(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String d = detail.toLowerCase(Locale.ROOT);
        return d.contains("not a valid chart repository")
                || d.contains("failed to load and validate chart")
                || d.contains("failed to find the helm chart")
                || (d.contains("index.yaml")
                        && (d.contains("cannot be reached")
                                || d.contains("x509")
                                || d.contains("404")
                                || d.contains("401")
                                || d.contains("403")));
    }

    /**
     * Lead with the innermost fetch failure (TLS / HTTP status) so operators do not mistake Helm's
     * generic "not a valid chart repository" wording for a path typo alone.
     */
    static String formatHelmChartRepositoryFailure(String detail) {
        String cleaned = detail == null ? "" : detail.trim();
        String root = extractInnermostGetFailure(cleaned);
        if (!root.isBlank()) {
            String kind = classifyChartRepoFetchFailure(root);
            return "Helm chart repository fetch failed (" + kind + "): " + root
                    + (cleaned.isBlank() || cleaned.contains(root) ? "" : " — " + cleaned);
        }
        return "Helm chart repository fetch failed: " + cleaned;
    }

    static String helmChartRepositoryHint(String detail) {
        if (detail != null && detail.contains("Hint:")) {
            return "";
        }
        String d = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (d.contains("x509") || d.contains("unknown authority") || d.contains("certificate")) {
            return " Hint: Portainer (not Jenkins) cannot trust the chart repo TLS certificate —"
                    + " add the chart host CA to Portainer truststore (no Helm CA UI)."
                    + " After TLS works, a missing chart often surfaces as HTTP 404 from index.yaml.";
        }
        if (d.contains("404") || d.contains("not found")) {
            return " Hint: chart repo returned 404 — publish the chart to GitLab Package Registry"
                    + " and use repo=…/packages/helm/<channel> (no /index.yaml) and chart=<name>.";
        }
        if (d.contains("401") || d.contains("403") || d.contains("unauthorized") || d.contains("denied")) {
            return " Hint: chart repo auth failed (HTTP 401/403) — Portainer Helm install has no"
                    + " username/password fields; this plugin cannot inject chart-repo credentials."
                    + " Use a repo Portainer can fetch without auth (network ACL / public Package Registry),"
                    + " or run helm outside Portainer until Portainer supports private chart repos.";
        }
        return " Hint: Portainer could not fetch the Helm repo index — check TLS CA, HTTP status (404/401),"
                + " and repo URL (…/packages/helm/<channel>, not a git project path).";
    }

    /** Last {@code Get "url": reason} clause from Helm/Go error text. */
    static String extractInnermostGetFailure(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "Get \"([^\"]+)\":\\s*([^\\n]+?)(?=(?:\\s+Get \")|$)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(detail);
        String last = "";
        while (m.find()) {
            last = "Get \"" + m.group(1) + "\": " + m.group(2).trim();
        }
        return last;
    }

    static String classifyChartRepoFetchFailure(String getFailure) {
        String d = getFailure == null ? "" : getFailure.toLowerCase(Locale.ROOT);
        if (d.contains("x509") || d.contains("unknown authority") || d.contains("certificate")) {
            return "TLS to chart repo from Portainer";
        }
        if (d.contains("404")) {
            return "HTTP 404 from chart repo";
        }
        if (d.contains("401") || d.contains("403")) {
            return "HTTP auth from chart repo";
        }
        if (d.contains("timeout") || d.contains("connection refused") || d.contains("no such host")) {
            return "network to chart repo";
        }
        return "chart repo unreachable";
    }

    static boolean isImagePullFailure(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String d = detail.toLowerCase(Locale.ROOT);
        return d.contains("pull access denied")
                || d.contains("error pulling")
                || d.contains("failed to pull")
                || d.contains("manifest unknown")
                || d.contains("not found: manifest")
                || d.contains("image pull")
                || d.contains("toomanyrequests")
                || (d.contains("pull")
                        && (d.contains("image") || d.contains("repository") || d.contains("denied")
                                || d.contains("unauthorized")));
    }

    static boolean looksLikeHtml(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return false;
        }
        int len = Math.min(bodyBytes.length, 256);
        String head = new String(bodyBytes, 0, len, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<html");
    }

    private static boolean isAuthFailureMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        if (isConnectivityMessage(msg)) {
            return false;
        }
        String d = msg.toLowerCase(Locale.ROOT);
        return d.contains("401")
                || d.contains("invalid or missing portainer api key")
                || (d.contains("403") && d.contains("lacks permission"));
    }

    private static String detailSuffix(String fullMessage) {
        if (fullMessage == null || fullMessage.isBlank()) {
            return "";
        }
        int dash = fullMessage.indexOf(" - ");
        if (dash >= 0 && dash + 3 < fullMessage.length()) {
            return " - " + truncateDetail(fullMessage.substring(dash + 3));
        }
        return "";
    }

    private static String timeoutMessage(URI uri) {
        String target = describeTarget(uri);
        return "Portainer request timed out"
                + (target.isBlank() ? "" : " (" + target + ")")
                + ". Check URL/port, network, and connect/read timeouts."
                + portHintSuffix(uri, "");
    }

    private static String connectivityMessage(URI uri, String detail) {
        String target = describeTarget(uri);
        StringBuilder sb = new StringBuilder("Cannot connect to Portainer");
        if (!target.isBlank()) {
            sb.append(" at ").append(target);
        }
        sb.append(" (network/connectivity)");
        if (detail != null && !detail.isBlank()) {
            sb.append(" - ").append(truncateDetail(detail.replaceAll("\\s+", " ")));
        }
        sb.append(portHintSuffix(uri, detail));
        return sb.toString();
    }

    private static String describeTarget(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return "";
        }
        int port = effectivePort(uri);
        return uri.getHost() + ":" + port;
    }

    private static int effectivePort(URI uri) {
        if (uri == null) {
            return -1;
        }
        int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        String scheme = uri.getScheme();
        if (scheme != null && scheme.equalsIgnoreCase("https")) {
            return 443;
        }
        if (scheme != null && scheme.equalsIgnoreCase("http")) {
            return 80;
        }
        return -1;
    }

    private static String portHintSuffix(URI uri, String detail) {
        int port = effectivePort(uri);
        if (port < 0 && detail != null) {
            // e.g. "connecting to host:80:"
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(":(\\d+)\\b").matcher(detail);
            if (m.find()) {
                try {
                    port = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    port = -1;
                }
            }
        }
        if (port == 80 || port == 443) {
            return " Portainer API usually listens on 9000 (HTTP) or 9443 (HTTPS)"
                    + " — confirm UI vs API URL/port.";
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String truncateDetail(String detail) {
        if (detail.length() <= MAX_ERROR_DETAIL_CHARS) {
            return detail;
        }
        return detail.substring(0, MAX_ERROR_DETAIL_CHARS) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    /** Docker Swarm config summary from {@code GET …/docker/configs}. */
    static final class DockerConfigSummary {
        final String id;
        final String name;
        final Map<String, String> labels;

        DockerConfigSummary(String id, String name, Map<String, String> labels) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.labels = labels == null ? Map.of() : labels;
        }
    }

    /** Payload for {@code POST …/docker/configs/create}. */
    static final class DockerConfigCreateRequest {
        final String name;
        final byte[] data;
        final Map<String, String> labels;

        DockerConfigCreateRequest(String name, byte[] data, Map<String, String> labels) {
            this.name = name;
            this.data = data == null ? new byte[0] : data.clone();
            this.labels = labels;
        }
    }

    /** Payload for {@code POST …/docker/secrets/create}. */
    static final class DockerSecretCreateRequest {
        final String name;
        final byte[] data;
        final Map<String, String> labels;

        DockerSecretCreateRequest(String name, byte[] data, Map<String, String> labels) {
            this.name = name;
            this.data = data == null ? new byte[0] : data.clone();
            this.labels = labels;
        }
    }

    /** Environment variable pair for Portainer {@code Env[]}. */
    static final class EnvPair {
        final String name;
        final String value;

        EnvPair(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /** Payload for create-from-repository (compose or swarm). */
    static final class StackFromGitRequest {
        final String name;
        final String repositoryUrl;
        final String composeFile;
        final String repositoryReferenceName;
        final String gitUsername;
        /** Ephemeral Portainer API payload; not Jenkins job config. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String gitPassword;
        final List<EnvPair> env;

        StackFromGitRequest(
                String name,
                String repositoryUrl,
                String composeFile,
                String repositoryReferenceName,
                String gitUsername,
                String gitPassword,
                List<EnvPair> env) {
            this.name = name;
            this.repositoryUrl = repositoryUrl;
            this.composeFile = composeFile;
            this.repositoryReferenceName = repositoryReferenceName;
            this.gitUsername = gitUsername;
            this.gitPassword = gitPassword;
            this.env = env;
        }
    }

    /** Payload for git redeploy. */
    static final class GitRedeployRequest {
        final List<EnvPair> env;
        final boolean prune;
        final boolean repullImageAndRedeploy;
        final String repositoryReferenceName;
        final String gitUsername;
        /** Ephemeral Portainer API payload; not Jenkins job config. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String gitPassword;

        GitRedeployRequest(
                List<EnvPair> env,
                boolean prune,
                boolean repullImageAndRedeploy,
                String repositoryReferenceName,
                String gitUsername,
                String gitPassword) {
            this.env = env;
            this.prune = prune;
            this.repullImageAndRedeploy = repullImageAndRedeploy;
            this.repositoryReferenceName = repositoryReferenceName;
            this.gitUsername = gitUsername;
            this.gitPassword = gitPassword;
        }
    }

    /** Payload for create-from-string (compose or swarm). */
    static final class StackFromStringRequest {
        final String name;
        final String stackFileContent;
        final List<EnvPair> env;

        StackFromStringRequest(String name, String stackFileContent, List<EnvPair> env) {
            this.name = name;
            this.stackFileContent = stackFileContent;
            this.env = env;
        }
    }

    /** Payload for {@code PUT /api/stacks/{id}} file content update. */
    static final class StackFileUpdateRequest {
        final String stackFileContent;
        final List<EnvPair> env;
        final boolean prune;
        final boolean repullImageAndRedeploy;

        StackFileUpdateRequest(
                String stackFileContent,
                List<EnvPair> env,
                boolean prune,
                boolean repullImageAndRedeploy) {
            this.stackFileContent = stackFileContent;
            this.env = env;
            this.prune = prune;
            this.repullImageAndRedeploy = repullImageAndRedeploy;
        }
    }

    /** Payload for Kubernetes create-from-string. */
    static final class KubernetesFromStringRequest {
        final String stackName;
        final String stackFileContent;
        final String namespace;

        KubernetesFromStringRequest(String stackName, String stackFileContent, String namespace) {
            this.stackName = stackName;
            this.stackFileContent = stackFileContent;
            this.namespace = namespace;
        }
    }

    /** Payload for Kubernetes create-from-repository. */
    static final class KubernetesFromGitRequest {
        final String stackName;
        final String repositoryUrl;
        final String manifestFile;
        final String repositoryReferenceName;
        final String namespace;
        final String gitUsername;
        /** Ephemeral Portainer API payload; not Jenkins job config. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String gitPassword;

        KubernetesFromGitRequest(
                String stackName,
                String repositoryUrl,
                String manifestFile,
                String repositoryReferenceName,
                String namespace,
                String gitUsername,
                String gitPassword) {
            this.stackName = stackName;
            this.repositoryUrl = repositoryUrl;
            this.manifestFile = manifestFile;
            this.repositoryReferenceName = repositoryReferenceName;
            this.namespace = namespace;
            this.gitUsername = gitUsername;
            this.gitPassword = gitPassword;
        }
    }

    /** Payload for Kubernetes file-content update. */
    static final class KubernetesFileUpdateRequest {
        final String stackFileContent;
        final String stackName;

        KubernetesFileUpdateRequest(String stackFileContent, String stackName) {
            this.stackFileContent = stackFileContent;
            this.stackName = stackName;
        }
    }

    /** Payload for Kubernetes Git stack update (not Docker git redeploy). */
    static final class KubernetesGitUpdateRequest {
        final String repositoryReferenceName;
        final String gitUsername;
        /** Ephemeral Portainer API payload; not Jenkins job config. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String gitPassword;

        KubernetesGitUpdateRequest(
                String repositoryReferenceName, String gitUsername, String gitPassword) {
            this.repositoryReferenceName = repositoryReferenceName;
            this.gitUsername = gitUsername;
            this.gitPassword = gitPassword;
        }
    }

    /** Payload for Helm install ({@code POST …/kubernetes/helm}). */
    static final class HelmInstallRequest {
        final String releaseName;
        final String chart;
        final String repo;
        final String namespace;
        final String version;
        final String values;
        final boolean atomic;

        HelmInstallRequest(
                String releaseName,
                String chart,
                String repo,
                String namespace,
                String version,
                String values,
                boolean atomic) {
            this.releaseName = releaseName;
            this.chart = chart;
            this.repo = repo;
            this.namespace = namespace;
            this.version = version;
            this.values = values;
            this.atomic = atomic;
        }
    }

}
