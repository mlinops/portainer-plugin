package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Item;
import hudson.model.Run;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Shared Vault KV v2 read for Secret (required) and Stack (optional soft-skip) contracts.
 * Does not merge those contracts — {@link Policy} selects path/error behavior.
 */
final class VaultKv {

    private static final Map<String, String> SKIP_VAULT = Collections.emptyMap();

    enum Policy {
        /** Secret step: path required; remap common not-found errors. */
        REQUIRED,
        /** Stack overlay: {@code MODE_NONE} / empty path soft-skip; Manual partial-config abort. */
        OPTIONAL_SOFT_SKIP
    }

    private VaultKv() {
    }

    /**
     * @return KV data from Vault; never {@code null}. Empty map means soft-skip under
     *         {@link Policy#OPTIONAL_SOFT_SKIP} (Vault off / empty path) or a successful read
     *         with no keys. Callers must treat empty like the former {@code null} (“do not overlay”).
     */
    static Map<String, String> resolve(Request req) throws AbortException {
        requireLog(req);
        String mode = ConnectionMode.normalize(
                req.mode, req.policy == Policy.REQUIRED ? ConnectionMode.INHERIT : ConnectionMode.NONE);
        if (ConnectionMode.isNone(mode)) {
            return resolveNone(req);
        }
        VaultFields fields = req.fields;
        if (fields == null || !nonBlank(fields.pathRaw) || fields.path == null) {
            return resolveMissingPath(req, mode, fields);
        }
        if (req.policy == Policy.OPTIONAL_SOFT_SKIP) {
            req.log.info(PortainerBuildLogger.formatVaultPath(fields.path, fields.version));
        }
        if (ConnectionMode.isInherit(mode)) {
            return readInherit(req, fields);
        }
        return readManual(req, fields);
    }

    private static void requireLog(Request req) throws AbortException {
        if (req == null || req.log == null) {
            throw new AbortException("Vault KV resolve requires a build logger.");
        }
    }

    private static Map<String, String> resolveNone(Request req) throws AbortException {
        if (req.policy == Policy.REQUIRED) {
            throw PortainerConnections.abort(req.log, "Vault connection is required.");
        }
        return SKIP_VAULT;
    }

    private static Map<String, String> resolveMissingPath(Request req, String mode, VaultFields fields)
            throws AbortException {
        if (req.policy == Policy.OPTIONAL_SOFT_SKIP) {
            if (ConnectionMode.isManual(mode)
                    && fields != null
                    && (nonBlank(fields.urlRaw) || nonBlank(req.appRoleCredentialsId))) {
                throw PortainerConnections.abort(
                        req.log,
                        "Vault Manual is partially configured: vaultPath is required "
                                + "(or set Vault connection to Not connected / clear vaultUrl "
                                + "and vaultAppRoleCredentialsId).");
            }
            return SKIP_VAULT;
        }
        throw PortainerConnections.abort(req.log, "Vault path is required.");
    }

    private static Map<String, String> readInherit(Request req, VaultFields fields) throws AbortException {
        if (fields.version != null) {
            req.log.warn("Vault Inherit: vaultVersion is ignored (HashiCorp Vault Plugin reads latest); "
                    + "use Manual for a specific KV version");
        }
        try {
            return VaultPluginInherit.readKvV2(
                    req.run, req.buildEnv, fields.mount, fields.path, fields.namespace,
                    req.log.getListener(), req.log);
        } catch (AbortException e) {
            throw remapInheritAbort(req, fields.path, e);
        }
    }

    private static AbortException remapInheritAbort(Request req, String path, AbortException e)
            throws AbortException {
        if (req.policy == Policy.REQUIRED) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.contains("not found") || lower.contains("error at path")) {
                throw PortainerConnections.abort(req.log, "Vault path not found: " + path);
            }
        }
        throw PortainerConnections.abort(req.log, e.getMessage());
    }

    private static Map<String, String> readManual(Request req, VaultFields fields) throws AbortException {
        String urlRaw = fields.urlRaw;
        String appRoleCred = req.appRoleCredentialsId;
        if (!nonBlank(urlRaw) || !nonBlank(appRoleCred)) {
            throw abortManualIncomplete(req);
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

        logManualRead(req, fields);

        int connectMs = req.connectTimeoutMs > 0
                ? req.connectTimeoutMs
                : PortainerGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS;
        int readMs = req.readTimeoutMs > 0
                ? req.readTimeoutMs
                : PortainerGlobalConfiguration.DEFAULT_READ_TIMEOUT_MS;
        try (VaultClient vault = new VaultClient(connectMs, readMs, req.log)) {
            return vault.readKvV2(new VaultClient.ReadRequest(
                    baseUrl, appRole.roleId, appRole.secretId,
                    fields.mount, fields.path, fields.namespace, fields.version));
        } catch (IOException e) {
            throw remapManualIo(req, fields.path, e);
        }
    }

    private static AbortException abortManualIncomplete(Request req) throws AbortException {
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

    private static void logManualRead(Request req, VaultFields fields) {
        StringBuilder debug = new StringBuilder("Vault Manual reading mount=")
                .append(fields.mount)
                .append(" path=")
                .append(fields.path);
        if (fields.version != null) {
            debug.append(" version=").append(fields.version);
        }
        if (nonBlank(fields.namespace)) {
            debug.append(" namespace=").append(fields.namespace);
        }
        req.log.debug(debug.toString());
    }

    private static AbortException remapManualIo(Request req, String path, IOException e)
            throws AbortException {
        String msg = PortainerConnections.truncateMessage(e);
        if (req.policy == Policy.REQUIRED
                && (msg.contains("404") || msg.toLowerCase(Locale.ROOT).contains("not found"))) {
            throw PortainerConnections.abort(req.log, "Vault path not found: " + path, e);
        }
        throw PortainerConnections.abort(req.log, "Vault failed: " + msg, e);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Vault KV resolve inputs. Prefer {@link #Request(VaultSpec, RunContext, Timeouts, PortainerBuildLogger)}.
     */
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

        Request(VaultSpec vault, RunContext runContext, Timeouts timeouts, PortainerBuildLogger log) {
            this.policy = vault.policy;
            this.mode = vault.mode;
            this.fields = vault.fields;
            this.appRoleCredentialsId = vault.appRoleCredentialsId;
            this.run = runContext.run;
            this.buildEnv = runContext.buildEnv;
            this.item = runContext.item;
            this.connectTimeoutMs = timeouts.connectTimeoutMs;
            this.readTimeoutMs = timeouts.readTimeoutMs;
            this.log = log;
        }

        /** Policy, connection mode, path fields, and AppRole credential id. */
        static final class VaultSpec {
            final Policy policy;
            final String mode;
            final VaultFields fields;
            final String appRoleCredentialsId;

            VaultSpec(Policy policy, String mode, VaultFields fields, String appRoleCredentialsId) {
                this.policy = policy;
                this.mode = mode;
                this.fields = fields;
                this.appRoleCredentialsId = appRoleCredentialsId;
            }
        }

        /** Build / job context for Inherit and credential lookup. */
        static final class RunContext {
            final Run<?, ?> run;
            final EnvVars buildEnv;
            final Item item;

            RunContext(Run<?, ?> run, EnvVars buildEnv, Item item) {
                this.run = run;
                this.buildEnv = buildEnv;
                this.item = item;
            }
        }

        /** HTTP timeouts (0 = use global defaults). */
        static final class Timeouts {
            final int connectTimeoutMs;
            final int readTimeoutMs;

            Timeouts(int connectTimeoutMs, int readTimeoutMs) {
                this.connectTimeoutMs = connectTimeoutMs;
                this.readTimeoutMs = readTimeoutMs;
            }
        }
    }
}
