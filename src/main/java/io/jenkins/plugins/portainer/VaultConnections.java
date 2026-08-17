package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Item;
import hudson.model.Run;

import java.io.IOException;

/**
 * Shared Vault preflight for build steps that already have Vault connection fields.
 * Inherit: plugin URL + {@code /v1/sys/health}. Manual: AppRole login + lookup-self + revoke.
 * Does not read KV data (Secret path emptiness is checked by the Secret step after KV read).
 * Never logs tokens or secret values.
 */
final class VaultConnections {

    /** When true, Vault network preflight is skipped (unit tests). */
    static volatile boolean testSkipPreflight;

    private VaultConnections() {
    }

    static void runPreflight(Request req) throws AbortException {
        requireLog(req);
        String mode = ConnectionMode.normalize(
                req.mode, req.required ? ConnectionMode.INHERIT : ConnectionMode.NONE);
        if (ConnectionMode.isNone(mode)) {
            handleNone(req);
            return;
        }

        String pathRaw = VaultFields.expandOptional(req.vaultPath, req.buildEnv);
        String urlRaw = VaultFields.expandOptional(req.vaultUrl, req.buildEnv);
        String namespace = VaultFields.expandOptional(req.vaultNamespace, req.buildEnv);

        if (pathRaw == null) {
            handleMissingPath(req, mode, urlRaw);
            return;
        }

        req.log.info("Preflight check of Vault");

        if (testSkipPreflight || PortainerSwarmSecretBuilder.testVaultOverride != null) {
            return;
        }

        probeVault(req, mode, urlRaw, namespace);
    }

    private static void requireLog(Request req) throws AbortException {
        if (req == null || req.log == null) {
            throw new AbortException("Vault preflight requires a build logger.");
        }
    }

    private static void handleNone(Request req) throws AbortException {
        if (req.required) {
            throw PortainerConnections.abort(req.log, "Vault connection is required.");
        }
    }

    private static void handleMissingPath(Request req, String mode, String urlRaw) throws AbortException {
        if (ConnectionMode.isManual(mode) && (urlRaw != null || nonBlank(req.appRoleCredentialsId))) {
            throw PortainerConnections.abort(
                    req.log,
                    "Vault Manual is partially configured: vaultPath is required "
                            + "(or set Vault connection to Not connected / clear vaultUrl "
                            + "and vaultAppRoleCredentialsId).");
        }
        if (!req.required) {
            return;
        }
        throw PortainerConnections.abort(req.log, "Vault path is required.");
    }

    private static void probeVault(Request req, String mode, String urlRaw, String namespace)
            throws AbortException {
        int connectMs = req.connectTimeoutMs > 0
                ? req.connectTimeoutMs
                : PortainerGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS;
        int readMs = req.readTimeoutMs > 0
                ? req.readTimeoutMs
                : PortainerGlobalConfiguration.DEFAULT_READ_TIMEOUT_MS;
        try (VaultClient vault = new VaultClient(connectMs, readMs, req.log)) {
            if (ConnectionMode.isInherit(mode)) {
                String baseUrl = VaultPluginInherit.resolveVaultUrl(req.run, namespace);
                vault.probeHealth(baseUrl, namespace);
                return;
            }
            if (urlRaw == null || !nonBlank(req.appRoleCredentialsId)) {
                throw PortainerConnections.abort(
                        req.log,
                        "Vault Manual requires vaultUrl and vaultAppRoleCredentialsId "
                                + "(Username/Password: role_id / secret_id), plus vaultPath.");
            }
            PortainerCredentials.AppRoleIds appRole =
                    PortainerCredentials.resolveAppRole(req.appRoleCredentialsId, req.item);
            vault.preflightAppRole(urlRaw, appRole.roleId, appRole.secretId, namespace);
        } catch (AbortException e) {
            throw PortainerConnections.abort(req.log, e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw PortainerConnections.abort(req.log, e.getMessage());
        } catch (IOException e) {
            throw PortainerConnections.abort(
                    req.log, "Vault preflight failed: " + PortainerConnections.truncateMessage(e), e);
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    static final class Request {
        final String mode;
        final boolean required;
        final String vaultUrl;
        final String appRoleCredentialsId;
        final String vaultPath;
        final String vaultNamespace;
        final Run<?, ?> run;
        final EnvVars buildEnv;
        final Item item;
        final int connectTimeoutMs;
        final int readTimeoutMs;
        final PortainerBuildLogger log;

        Request(
                String mode,
                boolean required,
                String vaultUrl,
                String appRoleCredentialsId,
                String vaultPath,
                String vaultNamespace,
                Run<?, ?> run,
                EnvVars buildEnv,
                Item item,
                int connectTimeoutMs,
                int readTimeoutMs,
                PortainerBuildLogger log) {
            this.mode = mode;
            this.required = required;
            this.vaultUrl = vaultUrl;
            this.appRoleCredentialsId = appRoleCredentialsId;
            this.vaultPath = vaultPath;
            this.vaultNamespace = vaultNamespace;
            this.run = run;
            this.buildEnv = buildEnv;
            this.item = item;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.log = log;
        }
    }
}
