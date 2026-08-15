package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Item;
import hudson.model.Run;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Shared Vault KV v2 read for Secret (required) and Stack (optional soft-skip) contracts.
 * Does not merge those contracts — {@link Policy} selects path/error behavior.
 */
final class VaultKv {

    enum Policy {
        /** Secret step: path required; remap common not-found errors. */
        REQUIRED,
        /** Stack overlay: {@code MODE_NONE} / empty path soft-skip; Manual partial-config abort. */
        OPTIONAL_SOFT_SKIP
    }

    private VaultKv() {
    }

    /**
     * @return KV data, empty map when Vault returned no keys, or {@code null} only for
     *         {@link Policy#OPTIONAL_SOFT_SKIP} when Vault is off or path is empty (soft-skip).
     */
    static Map<String, String> resolve(Request req) throws AbortException {
        if (req == null || req.log == null) {
            throw new AbortException("Vault KV resolve requires a build logger.");
        }
        String mode = ConnectionMode.normalize(
                req.mode, req.policy == Policy.REQUIRED ? ConnectionMode.INHERIT : ConnectionMode.NONE);
        if (ConnectionMode.isNone(mode)) {
            if (req.policy == Policy.REQUIRED) {
                throw PortainerConnections.abort(req.log, "Vault connection is required.");
            }
            return null;
        }

        VaultFields fields = req.fields;
        String appRoleCred = req.appRoleCredentialsId;

        if (fields == null || !nonBlank(fields.pathRaw) || fields.path == null) {
            if (req.policy == Policy.OPTIONAL_SOFT_SKIP) {
                if (ConnectionMode.isManual(mode)
                        && fields != null
                        && (nonBlank(fields.urlRaw) || nonBlank(appRoleCred))) {
                    throw PortainerConnections.abort(
                            req.log,
                            "Vault Manual is partially configured: vaultPath is required "
                                    + "(or set Vault connection to Not connected / clear vaultUrl "
                                    + "and vaultAppRoleCredentialsId).");
                }
                return null;
            }
            throw PortainerConnections.abort(req.log, "Vault path is required.");
        }

        String mount = fields.mount;
        String path = fields.path;
        Integer version = fields.version;
        String namespace = fields.namespace;
        String urlRaw = fields.urlRaw;

        if (req.policy == Policy.OPTIONAL_SOFT_SKIP) {
            req.log.info(PortainerBuildLogger.formatVaultPath(path, version));
        }

        if (ConnectionMode.isInherit(mode)) {
            if (version != null) {
                req.log.warn("Vault Inherit: vaultVersion is ignored (HashiCorp Vault Plugin reads latest); "
                        + "use Manual for a specific KV version");
            }
            try {
                return VaultPluginInherit.readKvV2(
                        req.run, req.buildEnv, mount, path, namespace, req.log.getListener(), req.log);
            } catch (AbortException e) {
                if (req.policy == Policy.REQUIRED) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    String lower = msg.toLowerCase(Locale.ROOT);
                    if (lower.contains("not found") || lower.contains("error at path")) {
                        throw PortainerConnections.abort(req.log, "Vault path not found: " + path);
                    }
                }
                throw PortainerConnections.abort(req.log, e.getMessage());
            }
        }

        boolean requiredOk = nonBlank(urlRaw) && nonBlank(appRoleCred);
        if (!requiredOk) {
            if (req.policy == Policy.OPTIONAL_SOFT_SKIP) {
                throw PortainerConnections.abort(
                        req.log,
                        "Vault Manual requires vaultUrl and vaultAppRoleCredentialsId "
                                + "(Username/Password: role_id / secret_id), plus vaultPath. "
                                + "Or set Vault connection to Inherit / Not connected.");
            }
            throw PortainerConnections.abort(
                    req.log,
                    "Vault Manual requires vaultUrl and vaultAppRoleCredentialsId "
                            + "(Username/Password: role_id / secret_id), plus vaultPath.");
        }

        final PortainerCredentials.AppRoleIds appRole;
        try {
            appRole = PortainerCredentials.resolveAppRole(appRoleCred, req.item);
        } catch (IllegalStateException e) {
            throw PortainerConnections.abort(req.log, e.getMessage());
        }

        final String baseUrl;
        try {
            baseUrl = VaultUrl.normalizeBaseUrl(urlRaw);
        } catch (IllegalArgumentException e) {
            throw PortainerConnections.abort(req.log, e.getMessage());
        }

        StringBuilder debug = new StringBuilder("Vault Manual reading mount=")
                .append(mount)
                .append(" path=")
                .append(path);
        if (version != null) {
            debug.append(" version=").append(version);
        }
        if (nonBlank(namespace)) {
            debug.append(" namespace=").append(namespace);
        }
        req.log.debug(debug.toString());

        int connectMs = req.connectTimeoutMs > 0
                ? req.connectTimeoutMs
                : PortainerGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS;
        int readMs = req.readTimeoutMs > 0
                ? req.readTimeoutMs
                : PortainerGlobalConfiguration.DEFAULT_READ_TIMEOUT_MS;
        try (VaultClient vault = new VaultClient(connectMs, readMs, req.log)) {
            Map<String, String> data = vault.readKvV2(new VaultClient.ReadRequest(
                    baseUrl, appRole.roleId, appRole.secretId, mount, path, namespace, version));
            return data == null ? Map.of() : data;
        } catch (IOException e) {
            String msg = PortainerConnections.truncateMessage(e);
            if (req.policy == Policy.REQUIRED
                    && (msg.contains("404") || msg.toLowerCase(Locale.ROOT).contains("not found"))) {
                throw PortainerConnections.abort(req.log, "Vault path not found: " + path, e);
            }
            throw PortainerConnections.abort(req.log, "Vault failed: " + msg, e);
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    static final class Request {
        final Policy policy;
        final String mode;
        final VaultFields fields;
        final String appRoleCredentialsId;
        final Run<?, ?> run;
        final EnvVars buildEnv;
        final Item item;
        final int connectTimeoutMs;
        final int readTimeoutMs;
        final PortainerBuildLogger log;

        Request(
                Policy policy,
                String mode,
                VaultFields fields,
                String appRoleCredentialsId,
                Run<?, ?> run,
                EnvVars buildEnv,
                Item item,
                int connectTimeoutMs,
                int readTimeoutMs,
                PortainerBuildLogger log) {
            this.policy = policy;
            this.mode = mode;
            this.fields = fields;
            this.appRoleCredentialsId = appRoleCredentialsId;
            this.run = run;
            this.buildEnv = buildEnv;
            this.item = item;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.log = log;
        }
    }
}
