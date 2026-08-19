package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
import com.datapipe.jenkins.vault.VaultAccessor;
import com.datapipe.jenkins.vault.configuration.GlobalVaultConfiguration;
import com.datapipe.jenkins.vault.configuration.VaultConfiguration;
import com.datapipe.jenkins.vault.credentials.VaultCredential;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.github.jopenlibs.vault.response.LogicalResponse;
import jenkins.model.Jenkins;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft integration with the official HashiCorp Vault Plugin for Vault <em>Inherit</em> mode.
 * <p>
 * Optional Maven dependency; {@link #isPluginPresent()} must be true before any Vault Plugin
 * type is loaded ({@link Api} is referenced only after that check).
 * <p>
 * Path for KV v2 is {@code mount/secretPath} (engine version 2); the Vault Java client inserts
 * {@code data/}. Step {@code vaultVersion} (KV secret version) is not supported via this API —
 * Inherit always reads the latest version.
 */
final class VaultPluginInherit {

    private static final Logger LOGGER = Logger.getLogger(VaultPluginInherit.class.getName());

    /** Update-center / {@code PluginWrapper} shortName (artifactId). */
    static final String VAULT_PLUGIN_SHORT_NAME = "hashicorp-vault-plugin";

    /** Vault HTTP path separator (always {@code /}, not {@link java.io.File#separator}). */
    private static final String VAULT_PATH_SEP = "/";

    static final String VAULT_PLUGIN_MISSING = "HashiCorp Vault Plugin is not installed.";

    static final String VAULT_PLUGIN_UNCONFIGURED = "Vault Plugin System is not configured.";

    private VaultPluginInherit() {
    }

    /**
     * Vault base URL from HashiCorp Vault Plugin (System + folder/job merge). Does not read KV.
     */
    static String resolveVaultUrl(Run<?, ?> run, String namespace) throws AbortException {
        requirePluginAndRun(run);
        try {
            return Api.resolveVaultUrl(run, namespace);
        } catch (AbortException e) {
            throw e;
        } catch (RuntimeException e) {
            throw mapPluginFailure(e);
        }
    }

    /**
     * Read all flat string keys from a KV v2 secret using Vault Plugin System (+ folder/job) config.
     *
     * @param mount      KV mount (e.g. {@code secret})
     * @param secretPath path within mount (e.g. {@code myapp/prod})
     * @param namespace  optional step override (Enterprise); blank keeps System/folder namespace
     * @param log        build logger (nullable; debug only)
     */
    static Map<String, String> readKvV2(
            Run<?, ?> run,
            EnvVars buildEnv,
            String mount,
            String secretPath,
            String namespace,
            TaskListener listener,
            PortainerBuildLogger log) throws AbortException {
        requirePluginAndRun(run);
        try {
            return Api.readKvV2(run, buildEnv, mount, secretPath, namespace, listener, log);
        } catch (AbortException e) {
            throw e;
        } catch (RuntimeException e) {
            throw mapPluginFailure(e);
        }
    }

    static boolean isPluginPresent() {
        PluginWrapper wrapper = findVaultPluginWrapper();
        return wrapper != null && wrapper.isActive();
    }

    /**
     * Best-effort check that Global Vault System config has a Vault URL (and credentials when
     * resolvable). Folder/job policies may still supply config at build time even if Global is empty.
     */
    static boolean isSystemConfigured() {
        if (!isPluginPresent()) {
            return false;
        }
        try {
            return Api.isSystemConfigured();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Vault Inherit: could not read GlobalVaultConfiguration", e);
            return false;
        }
    }

    /** UI / descriptor summary for Inherit mode — short readiness line. */
    static String inheritSummary() {
        if (!isPluginPresent()) {
            return "Vault Plugin is not installed.";
        }
        if (!isSystemConfigured()) {
            return "Vault Plugin is not configured.";
        }
        return "Vault Plugin is present and configured.";
    }

    static PluginWrapper findVaultPluginWrapper() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        return jenkins.getPluginManager().getPlugin(VAULT_PLUGIN_SHORT_NAME);
    }

    private static void requirePluginAndRun(Run<?, ?> run) throws AbortException {
        if (!isPluginPresent()) {
            throw new AbortException(VAULT_PLUGIN_MISSING);
        }
        if (run == null) {
            throw new AbortException("Vault Inherit requires a running build.");
        }
    }

    static AbortException mapPluginFailure(Throwable cause) {
        Throwable root = cause == null ? new RuntimeException() : cause;
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = root.getClass().getSimpleName();
        }
        if (msg.contains("No configuration found")
                || msg.contains("vault url was not configured")
                || msg.contains("credential id was not configured")
                || msg.contains("CredentialsUnavailable")
                || root instanceof CredentialsUnavailableException) {
            return new AbortException(VAULT_PLUGIN_UNCONFIGURED);
        }
        LOGGER.log(Level.WARNING, "Vault Inherit via HashiCorp Vault Plugin failed", root);
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        return new AbortException(
                "Vault Inherit failed: " + msg
                        + " Configure HashiCorp Vault Plugin System, or use Vault Manual on this step.");
    }

    /**
     * Direct calls into the Vault Plugin. Loaded only after {@link #isPluginPresent()} is true
     * so a missing optional plugin does not fail class linking of {@link VaultPluginInherit}.
     */
    private static final class Api {

        private Api() {
        }

        static boolean isSystemConfigured() {
            VaultConfiguration configuration = GlobalVaultConfiguration.get().getConfiguration();
            if (configuration == null) {
                return false;
            }
            String vaultUrl = configuration.getVaultUrl();
            if (vaultUrl == null || vaultUrl.isBlank()) {
                return false;
            }
            String credentialId = configuration.getVaultCredentialId();
            VaultCredential inlineCredential = configuration.getVaultCredential();
            return (credentialId != null && !credentialId.isBlank()) || inlineCredential != null;
        }

        static String resolveVaultUrl(Run<?, ?> run, String namespace) throws AbortException {
            return requireVaultUrl(mergeConfiguration(run, namespace));
        }

        static Map<String, String> readKvV2(
                Run<?, ?> run,
                EnvVars buildEnv,
                String mount,
                String secretPath,
                String namespace,
                TaskListener listener,
                PortainerBuildLogger log) throws AbortException {
            VaultConfiguration configuration = mergeConfiguration(run, namespace);
            requireVaultUrl(configuration);

            VaultAccessor accessor = prepareAccessor(run, buildEnv, configuration);
            String path = buildSecretPath(configuration, buildEnv, mount, secretPath);
            Integer engineVersion = configuration.getEngineVersion() == null
                    ? Integer.valueOf(2)
                    : configuration.getEngineVersion();
            logInheritRead(log, path, engineVersion, namespace);

            LogicalResponse response = accessor.read(path, engineVersion);
            PrintStream out = listener == null ? TaskListener.NULL.getLogger() : listener.getLogger();
            if (VaultAccessor.responseHasErrors(configuration, out, path, response)) {
                throw new AbortException(
                        "Vault Inherit: secret not found or error at path '" + path
                                + "' (see build log). Check path/mount and Vault Plugin configuration.");
            }
            return toFlatStringMap(response);
        }

        private static VaultConfiguration mergeConfiguration(Run<?, ?> run, String namespace) {
            VaultConfiguration stepOverrides = new VaultConfiguration();
            if (namespace != null && !namespace.isBlank()) {
                stepOverrides.setVaultNamespace(namespace.trim());
            }
            stepOverrides.setEngineVersion(2);
            return VaultAccessor.pullAndMergeConfiguration(run, stepOverrides);
        }

        private static String requireVaultUrl(VaultConfiguration configuration) throws AbortException {
            String vaultUrl = configuration == null ? null : configuration.getVaultUrl();
            if (vaultUrl == null || vaultUrl.isBlank()) {
                throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
            }
            return vaultUrl.trim();
        }

        private static VaultAccessor prepareAccessor(
                Run<?, ?> run,
                EnvVars buildEnv,
                VaultConfiguration configuration) throws AbortException {
            VaultCredential credential = configuration.getVaultCredential();
            String credentialId = configuration.getVaultCredentialId();
            if ((credentialId == null || credentialId.isBlank()) && credential == null) {
                throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
            }
            if (credential == null) {
                credential = VaultAccessor.retrieveVaultCredentials(run, configuration);
            }
            VaultAccessor accessor = new VaultAccessor();
            accessor.setConfig(configuration.getVaultConfig());
            accessor.setCredential(credential);
            applyPolicies(accessor, configuration, buildEnv);
            accessor.setMaxRetries(configuration.getMaxRetries());
            accessor.setRetryIntervalMilliseconds(configuration.getRetryIntervalMilliseconds());
            accessor.init();
            return accessor;
        }

        private static void applyPolicies(
                VaultAccessor accessor, VaultConfiguration configuration, EnvVars buildEnv) {
            try {
                accessor.setPolicies(PolicyTemplates.expand(configuration.getPolicies(), buildEnv));
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Vault Inherit: could not apply policy templates", e);
            }
        }

        private static String buildSecretPath(
                VaultConfiguration configuration, EnvVars buildEnv, String mount, String secretPath) {
            String prefixPath = configuration.getPrefixPath();
            String prefix = "";
            if (prefixPath != null && !prefixPath.isBlank()) {
                String expanded = buildEnv == null ? prefixPath.trim() : buildEnv.expand(prefixPath.trim());
                prefix = Util.ensureEndsWith(expanded, VAULT_PATH_SEP);
            }
            return prefix + mount + VAULT_PATH_SEP + secretPath;
        }

        private static void logInheritRead(
                PortainerBuildLogger log, String path, Integer engineVersion, String namespace) {
            if (log == null) {
                return;
            }
            log.debug("Vault Inherit reading path=" + path
                    + " engineVersion=" + engineVersion
                    + (namespace != null && !namespace.isBlank()
                            ? " namespace=" + namespace.trim()
                            : ""));
        }

        private static Map<String, String> toFlatStringMap(LogicalResponse response) {
            Map<String, String> data = response == null ? null : response.getData();
            if (data == null || data.isEmpty()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : data.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                out.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
            return out;
        }

        private static final class PolicyTemplates extends VaultAccessor {
            static List<String> expand(String policies, EnvVars envVars) {
                List<?> generated = generatePolicies(
                        policies, envVars == null ? new EnvVars() : envVars);
                if (generated == null) {
                    return null;
                }
                List<String> out = new ArrayList<>(generated.size());
                for (Object item : generated) {
                    if (item != null) {
                        out.add(item.toString());
                    }
                }
                return out;
            }
        }
    }
}
