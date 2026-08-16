package io.jenkins.plugins.portainer;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Item;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Shared Portainer connection resolve, endpoint parse, preflight, and abort helpers
 * for Stack / Manifest / Helm build steps.
 */
final class PortainerConnections {

    private PortainerConnections() {
    }

    static ResolvedConnection resolve(PortainerGlobalConfiguration cfg) throws AbortException {
        return resolve(cfg, ConnectionMode.INHERIT, null, null);
    }

    static ResolvedConnection resolve(
            PortainerGlobalConfiguration cfg,
            String mode,
            String manualUrl,
            String manualCredentialsId) throws AbortException {
        String normalizedMode = ConnectionMode.normalize(mode, ConnectionMode.INHERIT);
        if (ConnectionMode.isManual(normalizedMode)) {
            if (manualUrl == null || manualUrl.isBlank()
                    || manualCredentialsId == null || manualCredentialsId.isBlank()) {
                throw new AbortException(
                        "Portainer Manual requires Portainer URL and API key credentials on this step "
                                + "(or set Portainer connection to Inherit and configure Manage Jenkins → System).");
            }
            final String baseUrl;
            try {
                baseUrl = PortainerUrl.normalizeBaseUrl(manualUrl);
            } catch (IllegalArgumentException e) {
                throw new AbortException(e.getMessage());
            }
            int connectMs = PortainerGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS;
            int readMs = PortainerGlobalConfiguration.DEFAULT_READ_TIMEOUT_MS;
            if (cfg != null) {
                connectMs = cfg.getConnectTimeoutMs();
                readMs = cfg.getReadTimeoutMs();
            }
            return new ResolvedConnection(
                    "manual",
                    ConnectionMode.MANUAL,
                    baseUrl,
                    manualCredentialsId.trim(),
                    connectMs,
                    readMs);
        }

        if (cfg == null || !cfg.isConfigured()) {
            throw new AbortException(
                    "Portainer is not configured. Set Portainer URL and API key credentials under Manage Jenkins → System → Portainer "
                            + "(or set Portainer connection to Manual on this step).");
        }
        String displayName = cfg.getName() == null ? "" : cfg.getName().trim();
        if (displayName.isEmpty()) {
            displayName = "default";
        }
        final String baseUrl;
        try {
            baseUrl = PortainerUrl.normalizeBaseUrl(cfg.getPortainerUrl());
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        return new ResolvedConnection(
                displayName,
                ConnectionMode.INHERIT,
                baseUrl,
                cfg.getCredentialsId().trim(),
                cfg.getConnectTimeoutMs(),
                cfg.getReadTimeoutMs());
    }

    static int parseEndpointId(String raw) throws AbortException {
        if (raw == null || raw.isBlank()) {
            throw new AbortException("Endpoint ID is required (Portainer environment id).");
        }
        try {
            int id = Integer.parseInt(raw.trim());
            if (id <= 0) {
                throw new AbortException("Endpoint ID must be a positive integer.");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new AbortException("Endpoint ID must be a positive integer (got '" + raw.trim() + "').");
        }
    }

    /**
     * Expands {@code $VAR}/{@code ${VAR}} from the build environment, then parses a positive int.
     */
    static int resolveEndpointId(String raw, EnvVars buildEnv) throws AbortException {
        String source = raw == null ? "" : raw.trim();
        String expanded = buildEnv == null ? source : buildEnv.expand(source);
        return parseEndpointId(expanded);
    }

    /**
     * Build-time preflight: API reachable + endpoint exists. Optionally require a Kubernetes
     * environment ({@code Type} 5 / 6 / 7) and probe {@code GET …/kubernetes/version}.
     */
    static void runPreflight(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpointId,
            boolean requireKubernetes,
            PortainerBuildLogger log) throws AbortException {
        runPreflight(client, connection, apiKey, endpointId, requireKubernetes, false, log);
    }

    static void runPreflight(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpointId,
            boolean requireKubernetes,
            boolean probeKubernetesConnectivity,
            PortainerBuildLogger log) throws AbortException {
        try {
            PortainerClient.ProbeDetails probe = client.probeAccess(connection.baseUrl, apiKey);
            JsonNode endpointNode = client.getEndpoint(connection.baseUrl, apiKey, endpointId);
            String endpointName = endpointNode.path("Name").asText("");
            if (endpointName.isBlank()) {
                endpointName = endpointNode.path("name").asText("");
            }
            int type = endpointNode.path("Type").asInt(Integer.MIN_VALUE);
            if (type == Integer.MIN_VALUE) {
                type = endpointNode.path("type").asInt(Integer.MIN_VALUE);
            }
            if (requireKubernetes && !PortainerEndpointKinds.isKubernetes(type)) {
                throw abort(log, PortainerEndpointKinds.rejectNonKubernetesMessage(endpointId, type, endpointName));
            }
            if (requireKubernetes && probeKubernetesConnectivity) {
                client.probeKubernetesVersion(connection.baseUrl, apiKey, endpointId);
            }
            log.info(PortainerBuildLogger.formatPreflightEndpoint(endpointId, endpointName));
            StringBuilder debug = new StringBuilder("Portainer version=")
                    .append(preflightVersion(probe));
            if (type != Integer.MIN_VALUE) {
                debug.append(" type=").append(type);
            }
            log.debug(debug.toString());
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            String msg = truncateMessage(e);
            String hint = PortainerClient.kubernetesConnectivityHint(msg);
            throw abort(log, "Preflight failed: " + msg + hint, e);
        }
    }

    static String preflightVersion(PortainerClient.ProbeDetails probe) {
        String label = probe == null ? "" : probe.primaryLabel();
        if (label == null || label.isBlank()) {
            return "unknown";
        }
        if (label.startsWith("Portainer v")) {
            String v = label.substring("Portainer v".length()).trim();
            return v.isEmpty() ? "unknown" : v;
        }
        if (label.startsWith("Portainer ")) {
            return label.substring("Portainer ".length()).trim();
        }
        return label;
    }

    static AbortException abort(PortainerBuildLogger log, String message) {
        return abort(log, message, null, false);
    }

    static AbortException abort(PortainerBuildLogger log, String message, Throwable thrown) {
        return abort(log, message, thrown, false);
    }

    static AbortException abort(
            PortainerBuildLogger log, String message, Throwable thrown, boolean consoleStackTrace) {
        if (thrown instanceof PortainerLoggedAbort already) {
            return already;
        }
        String body = PortainerBuildLogger.stripPortainerPrefix(message);
        if (body.isEmpty()) {
            body = "failed";
        }
        if (log != null && !log.hasLoggedError()) {
            if (consoleStackTrace && thrown != null) {
                log.error(body, thrown);
            } else if (thrown != null) {
                log.errorJul(body, thrown);
                log.error(body);
            } else {
                log.error(body);
            }
        }
        return new PortainerLoggedAbort(body);
    }

    static String truncateMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        if (msg.length() > PortainerClient.MAX_ERROR_DETAIL_CHARS) {
            msg = msg.substring(0, PortainerClient.MAX_ERROR_DETAIL_CHARS) + "…";
        }
        return msg;
    }

    /** SHA-256 hex truncated for logs — never the YAML body. */
    static String shortContentHash(String content) {
        if (content == null || content.isEmpty()) {
            return "-";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < dig.length; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    static String connectionSummary() {
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        if (cfg == null || !cfg.isConfigured()) {
            return "System Portainer is not configured.";
        }
        return "System Portainer is configured.";
    }

    static FormValidation checkPortainerSource(String portainerConnectionMode) {
        if (ConnectionMode.isManual(portainerConnectionMode)) {
            return FormValidation.ok();
        }
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        if (cfg == null || !cfg.isConfigured()) {
            return FormValidation.error("System Portainer is not configured.");
        }
        return FormValidation.ok();
    }

    static void checkConfigure(Item item) {
        if (item == null) {
            Jenkins.get().checkPermission(Jenkins.READ);
        } else {
            item.checkPermission(Item.CONFIGURE);
        }
    }

    static FormValidation checkEndpointId(String value, String portainerConnectionMode) {
        FormValidation connection = checkPortainerSource(portainerConnectionMode);
        if (connection.kind == FormValidation.Kind.ERROR) {
            return connection;
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("Endpoint ID is required.");
        }
        String trimmed = value.trim();
        // Build-time expansion: ${VAR} / $VAR — validated after expand in resolveEndpointId.
        if (trimmed.indexOf('$') >= 0) {
            return FormValidation.ok();
        }
        try {
            int id = Integer.parseInt(trimmed);
            if (id <= 0) {
                return FormValidation.error("Endpoint ID must be a positive integer.");
            }
        } catch (NumberFormatException e) {
            return FormValidation.error("Endpoint ID must be a positive integer or ${VAR}.");
        }
        return FormValidation.ok();
    }

    static FormValidation checkPortainerUrl(String value, String portainerConnectionMode) {
        if (!ConnectionMode.isManual(portainerConnectionMode)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("Portainer URL is required for Manual connection.");
        }
        try {
            PortainerUrl.normalizeBaseUrlSyntaxOnly(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    /**
     * Runs {@code supplier} and rethrows as a logged {@link AbortException}
     * for {@link AbortException}, {@link IllegalArgumentException}, and {@link IllegalStateException}.
     */
    static <T> T abortOn(PortainerBuildLogger log, AbortingSupplier<T> supplier)
            throws AbortException {
        try {
            return supplier.get();
        } catch (AbortException e) {
            throw abort(log, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw abort(log, e.getMessage());
        }
    }

    /**
     * Resolves Portainer connection + API key for a step. Client lifecycle and preflight stay
     * in each {@code performBody} (order vs Git/Vault differs per step).
     */
    static Authenticated resolveAuthenticated(
            PortainerGlobalConfiguration cfg,
            String mode,
            String manualUrl,
            String manualCredentialsId,
            Item item,
            PortainerBuildLogger log) throws AbortException {
        ResolvedConnection connection = abortOn(
                log, () -> resolve(cfg, mode, manualUrl, manualCredentialsId));
        String apiKey = abortOn(
                log, () -> PortainerCredentials.resolveApiKey(connection.credentialsId, item));
        return new Authenticated(connection, apiKey);
    }

    /** Optional Git credentials; blank id → {@code null} (no auth). */
    static PortainerCredentials.GitAuth resolveOptionalGitAuth(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        return PortainerCredentials.resolveGitAuth(credentialsId, item);
    }

    /** Optional Git credentials with logged abort on resolve failure. */
    static PortainerCredentials.GitAuth resolveOptionalGitAuth(
            String credentialsId, Item item, PortainerBuildLogger log) throws AbortException {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        return abortOn(log, () -> PortainerCredentials.resolveGitAuth(credentialsId, item));
    }

    /**
     * Ephemeral Portainer session: connection + API key resolved from Credentials at runtime.
     * Not DataBound — jobs/System store credentialsId only.
     */
    static final class Authenticated {
        final ResolvedConnection connection;
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String apiKey;

        Authenticated(ResolvedConnection connection, String apiKey) {
            this.connection = connection;
            this.apiKey = apiKey;
        }
    }

    @FunctionalInterface
    interface AbortingSupplier<T> {
        T get() throws AbortException;
    }
}
