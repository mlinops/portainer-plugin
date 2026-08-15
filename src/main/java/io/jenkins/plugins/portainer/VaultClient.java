package io.jenkins.plugins.portainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ProxyConfiguration;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal HashiCorp Vault HTTP client: AppRole login + KV v2 read + revoke-self.
 * Never logs tokens, secret_id, or secret values. Does not cache client tokens across calls.
 * One {@link HttpClient} per instance; close after the step / probe finishes.
 */
final class VaultClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VaultClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String DEFAULT_MOUNT = "secret";

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final PortainerBuildLogger buildLog;
    private final HttpClient http;

    VaultClient(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, null);
    }

    VaultClient(int connectTimeoutMs, int readTimeoutMs, PortainerBuildLogger buildLog) {
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
     * AppRole login then KV v2 read → flat string map of secret keys.
     * Best-effort {@code POST /v1/auth/token/revoke-self} after the read (same client token);
     * revoke failure does not hide KV success.
     */
    Map<String, String> readKvV2(ReadRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String base = VaultUrl.normalizeBaseUrl(request.vaultUrl);
        String token = loginAppRole(base, request.roleId, request.secretId, request.namespace);
        try {
            return readSecret(base, token, request);
        } finally {
            revokeSelf(base, token, request.namespace);
        }
    }

    /**
     * AppRole login + {@code GET /v1/auth/token/lookup-self} + revoke-self.
     * Does not read KV data. Never logs tokens.
     */
    void preflightAppRole(String vaultUrl, String roleId, String secretId, String namespace)
            throws IOException {
        String base = VaultUrl.normalizeBaseUrl(vaultUrl);
        String token = loginAppRole(base, roleId, secretId, namespace);
        try {
            httpJson(
                    "GET",
                    base + "/v1/auth/token/lookup-self",
                    token,
                    namespace,
                    null,
                    "Vault token lookup-self",
                    false,
                    Set.of());
        } finally {
            revokeSelf(base, token, namespace);
        }
    }

    /**
     * Unauthenticated {@code GET /v1/sys/health}. Treats standby (429/473) as reachable.
     * Sealed (503) / uninitialized (501) abort.
     */
    void probeHealth(String vaultUrl, String namespace) throws IOException {
        String base = VaultUrl.normalizeBaseUrl(vaultUrl);
        JsonNode root;
        try {
            root = httpJson(
                    "GET",
                    base + "/v1/sys/health",
                    null,
                    namespace,
                    null,
                    "Vault health",
                    false,
                    Set.of(429, 473));
        } catch (IOException e) {
            throw mapHealthError(e);
        }
        if (root != null && root.path("sealed").asBoolean(false)) {
            throw new IOException("Vault is sealed.");
        }
        if (root != null && root.has("initialized") && !root.path("initialized").asBoolean(true)) {
            throw new IOException("Vault is not initialized.");
        }
    }

    private static IOException mapHealthError(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("503")) {
            return new IOException("Vault is sealed.", e);
        }
        if (msg.contains("501")) {
            return new IOException("Vault is not initialized.", e);
        }
        return e;
    }

    /**
     * Best-effort revoke of the AppRole-issued client token. Never throws; never logs the token.
     */
    private void revokeSelf(String base, String token, String namespace) {
        if (token == null || token.isBlank()) {
            return;
        }
        long startedNs = System.nanoTime();
        try {
            httpJson(
                    "POST",
                    base + "/v1/auth/token/revoke-self",
                    token,
                    namespace,
                    null,
                    "Vault token revoke-self",
                    true);
            long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
            if (buildLog != null) {
                buildLog.http("POST", "/v1/auth/token/revoke-self", durationMs);
            } else {
                LOGGER.log(Level.FINE, "Vault token revoke-self succeeded");
            }
        } catch (IOException e) {
            long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.toString();
            }
            String softFail = softFailStatus(detail);
            if (buildLog != null) {
                buildLog.http("POST", "/v1/auth/token/revoke-self", durationMs, "soft-fail: " + softFail);
                buildLog.warn("Vault revoke-self failed (token left for Vault TTL)");
            } else {
                LOGGER.log(
                        Level.WARNING,
                        "Vault token revoke-self failed (Manual client token may remain valid until TTL): {0}",
                        detail);
            }
        }
    }

    private static String softFailStatus(String detail) {
        if (detail == null || detail.isBlank()) {
            return "failed";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("HTTP\\s+(\\d{3})").matcher(detail);
        if (m.find()) {
            return "HTTP " + m.group(1);
        }
        String truncated = detail.length() > 80 ? detail.substring(0, 80) + "…" : detail;
        return truncated.replaceAll("\\s+", " ").trim();
    }

    private String loginAppRole(String base, String roleId, String secretId, String namespace)
            throws IOException {
        if (roleId == null || roleId.isBlank() || secretId == null || secretId.isBlank()) {
            throw new IOException("Vault AppRole role_id and secret_id are required.");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("role_id", roleId);
        body.put("secret_id", secretId);
        JsonNode auth = httpJson(
                "POST",
                base + "/v1/auth/approle/login",
                null,
                namespace,
                body,
                "Vault AppRole login",
                false);
        String token = text(auth.path("auth"), "client_token");
        if (token.isBlank()) {
            throw new IOException("Vault AppRole login did not return a client token.");
        }
        return token;
    }

    private Map<String, String> readSecret(String base, String token, ReadRequest request)
            throws IOException {
        String mount = normalizeMount(request.mount);
        String path = normalizeSecretPath(request.path);
        StringBuilder url = new StringBuilder(base)
                .append("/v1/")
                .append(encodePathSegments(mount))
                .append("/data/")
                .append(encodePathSegments(path));
        if (request.version != null && request.version > 0) {
            url.append("?version=").append(request.version);
        }
        JsonNode root;
        try {
            root = httpJson("GET", url.toString(), token, request.namespace, null, "Vault KV v2 read", false);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404")) {
                throw new IOException(
                        "Vault KV v2 secret not found (check mount '" + mount
                                + "' and path '" + path + "')"
                                + detailSuffixSafe(msg),
                        e);
            }
            throw e;
        }
        JsonNode dataWrapper = root.path("data");
        if (dataWrapper.isMissingNode() || dataWrapper.isNull()) {
            throw new IOException(
                    "Vault response is not KV v2 (missing data wrapper); only KV v2 is supported.");
        }
        JsonNode secretData = dataWrapper.path("data");
        if (secretData.isMissingNode() || secretData.isNull() || !secretData.isObject()) {
            throw new IOException(
                    "Vault response is not KV v2 secret data (expected data.data object); only KV v2 is supported.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        var fields = secretData.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            JsonNode v = entry.getValue();
            if (v == null || v.isNull()) {
                out.put(key, "");
                continue;
            }
            if (v.isObject() || v.isArray()) {
                throw new IOException(
                        "Vault secret key '" + key + "' is not a flat string value (nested JSON not supported).");
            }
            out.put(key, v.asText(""));
        }
        return out;
    }

    /**
     * Mount name only (no slashes). Default {@link #DEFAULT_MOUNT}.
     */
    static String normalizeMount(String mount) {
        if (mount == null || mount.isBlank()) {
            return DEFAULT_MOUNT;
        }
        String m = mount.trim().replaceAll("^/+|/+$", "");
        if (m.isEmpty() || m.contains("/")) {
            throw new IllegalArgumentException(
                    "Vault mount must be a single path segment (e.g. secret), got '" + truncate(mount.trim(), 40) + "'.");
        }
        return m;
    }

    /**
     * Path within the KV v2 mount (no leading {@code data/} segment).
     */
    static String normalizeSecretPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Vault path is required (e.g. myapp/prod).");
        }
        String p = path.trim().replaceAll("^/+|/+$", "");
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Vault path is required (e.g. myapp/prod).");
        }
        // Accept UI-style "secret/data/myapp/prod" when mount is separate — strip leading data/
        if (p.startsWith("data/")) {
            p = p.substring("data/".length()).replaceAll("^/+", "");
        }
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Vault path is required (e.g. myapp/prod).");
        }
        if (p.contains("..")) {
            throw new IllegalArgumentException("Vault path must not contain '..'.");
        }
        return p;
    }

    static Integer parseVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v <= 0) {
                throw new IllegalArgumentException("Vault version must be a positive integer.");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Vault version must be a positive integer.");
        }
    }

    private JsonNode httpJson(
            String method,
            String apiUrl,
            String vaultToken,
            String namespace,
            JsonNode body,
            String opLabel,
            boolean skipBuildHttpLog) throws IOException {
        return httpJson(method, apiUrl, vaultToken, namespace, body, opLabel, skipBuildHttpLog, Set.of());
    }

    private JsonNode httpJson(
            String method,
            String apiUrl,
            String vaultToken,
            String namespace,
            JsonNode body,
            String opLabel,
            boolean skipBuildHttpLog,
            Set<Integer> extraSuccessCodes) throws IOException {
        final URI uri;
        try {
            uri = URI.create(apiUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid Vault API URL", e);
        }
        try {
            ConnectionTester.assertHostAllowed(apiUrl, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            ConnectionTester.assertUriHostAllowed(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1, readTimeoutMs)))
                .header("Accept", "application/json");
        if (vaultToken != null && !vaultToken.isBlank()) {
            req.header("X-Vault-Token", vaultToken);
        }
        if (namespace != null && !namespace.isBlank()) {
            req.header("X-Vault-Namespace", namespace.trim());
        }

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
            throw new IOException(opLabel + " interrupted", e);
        } catch (IOException e) {
            throw mapTransportError(uri, e, opLabel);
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
        Set<Integer> extra = extraSuccessCodes == null ? Set.of() : extraSuccessCodes;
        boolean ok = (code >= 200 && code < 300) || extra.contains(code);
        if (!ok) {
            if (!skipBuildHttpLog && buildLog != null) {
                buildLog.http(m, path, durationMs);
            }
            throw httpError(code, bytes, opLabel);
        }
        if (!skipBuildHttpLog && buildLog != null) {
            buildLog.http(m, path, durationMs);
        }
        if (bytes.length == 0) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new IOException(opLabel + " returned non-JSON response.", e);
        }
    }

    static IOException httpError(int code, byte[] bodyBytes, String opLabel) {
        String label = opLabel == null || opLabel.isBlank() ? "Vault" : opLabel;
        String detail = extractErrors(bodyBytes);
        String suffix = detail.isBlank() ? "" : " - " + detail;
        if (code == 401 || code == 403) {
            return new IOException(
                    "HTTP " + code + " - Vault authentication or permission denied (" + label + ")" + suffix);
        }
        if (code == 404) {
            return new IOException("HTTP 404 - Vault path or secret not found (" + label + ")" + suffix);
        }
        return new IOException("HTTP " + code + " - " + label + " failed" + suffix);
    }

    /**
     * Vault often returns {@code {"errors":["..."]}}. Never echo request secrets.
     */
    static String extractErrors(byte[] bodyBytes) {
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
            if (errors != null && errors.isArray() && errors.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode e : errors) {
                    String t = e.asText("").trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (looksLikeSecret(t)) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(t);
                }
                return truncate(sb.toString(), 200);
            }
            String msg = firstNonBlank(text(node, "message"), text(node, "error"));
            if (!msg.isBlank() && !looksLikeSecret(msg)) {
                return truncate(msg, 200);
            }
        } catch (IOException ignored) {
            // fall through
        }
        // Avoid dumping raw bodies that might include echoed material
        return "";
    }

    private static boolean looksLikeSecret(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String d = text.toLowerCase(Locale.ROOT);
        return d.contains("secret_id")
                || d.contains("role_id")
                || d.contains("client_token")
                || d.contains("hvs.");
    }

    private static IOException mapTransportError(URI uri, IOException e, String opLabel) {
        if (e instanceof HttpTimeoutException) {
            return new IOException(opLabel + " timed out. Check Vault URL, network, and timeouts.", e);
        }
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                String host = uri == null ? null : uri.getHost();
                return new IOException(
                        "Vault host could not be resolved"
                                + (host == null || host.isBlank() ? "." : ": " + host + "."),
                        e);
            }
            if (t instanceof ConnectException) {
                return new IOException(
                        "Cannot connect to Vault"
                                + (uri == null || uri.getHost() == null ? "" : " at " + uri.getHost())
                                + " (network/connectivity).",
                        e);
            }
            t = t.getCause();
        }
        LOGGER.log(Level.FINE, "{0} transport error: {1}", new Object[]{opLabel, e.toString()});
        return new IOException(opLabel + " failed (network/connectivity).", e);
    }

    private static String encodePathSegments(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String detailSuffixSafe(String fullMessage) {
        if (fullMessage == null || fullMessage.isBlank()) {
            return "";
        }
        int dash = fullMessage.indexOf(" - ");
        if (dash >= 0 && dash + 3 < fullMessage.length()) {
            String d = fullMessage.substring(dash + 3).trim();
            if (d.isEmpty() || looksLikeSecret(d)) {
                return "";
            }
            return " - " + truncate(d, 120);
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

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    static final class ReadRequest {
        final String vaultUrl;
        final String roleId;
        final String secretId;
        final String mount;
        final String path;
        final String namespace;
        final Integer version;

        ReadRequest(
                String vaultUrl,
                String roleId,
                String secretId,
                String mount,
                String path,
                String namespace,
                Integer version) {
            this.vaultUrl = vaultUrl;
            this.roleId = roleId;
            this.secretId = secretId;
            this.mount = mount;
            this.path = path;
            this.namespace = namespace;
            this.version = version;
        }
    }
}
