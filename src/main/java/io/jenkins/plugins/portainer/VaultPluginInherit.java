package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft integration with the official HashiCorp Vault Plugin for Vault <em>Inherit</em> mode.
 * <p>
 * Uses reflection so Manual Vault AppRole HTTP works without the Vault Plugin on the classpath.
 * Detection uses {@link Jenkins#getPluginManager()} shortName ({@code hashicorp-vault-plugin}) and
 * loads API classes from that plugin's classloader (or the uber classloader) — bare
 * {@code Class.forName} on this plugin's CL cannot see optional peer plugins.
 * <p>
 * Calls public APIs: {@code VaultAccessor.pullAndMergeConfiguration},
 * {@code retrieveVaultCredentials}, {@code init}, {@code read}.
 * <p>
 * Path for KV v2 is {@code mount/secretPath} (engine version 2); the Vault Java client inserts
 * {@code data/}. Step {@code vaultVersion} (KV secret version) is not supported via this API —
 * Inherit always reads the latest version.
 */
final class VaultPluginInherit {

    private static final Logger LOGGER = Logger.getLogger(VaultPluginInherit.class.getName());

    /** Update-center / {@code PluginWrapper} shortName (artifactId). */
    static final String VAULT_PLUGIN_SHORT_NAME = "hashicorp-vault-plugin";

    /** Historical / alternate ids seen in the wild (checked after the primary shortName). */
    private static final String[] VAULT_PLUGIN_SHORT_NAME_ALIASES = {"vault-plugin"};

    private static final String CLASS_VAULT_ACCESSOR = "com.datapipe.jenkins.vault.VaultAccessor";
    private static final String CLASS_VAULT_CONFIGURATION =
            "com.datapipe.jenkins.vault.configuration.VaultConfiguration";
    private static final String CLASS_GLOBAL_VAULT_CONFIGURATION =
            "com.datapipe.jenkins.vault.configuration.GlobalVaultConfiguration";
    private static final String CLASS_VAULT_CREDENTIAL =
            "com.datapipe.jenkins.vault.credentials.VaultCredential";
    private static final String CLASS_VAULT_CONFIG = "io.github.jopenlibs.vault.VaultConfig";
    private static final String CLASS_LOGICAL_RESPONSE =
            "io.github.jopenlibs.vault.response.LogicalResponse";

    static final String VAULT_PLUGIN_MISSING = "HashiCorp Vault Plugin is not installed.";

    static final String VAULT_PLUGIN_UNCONFIGURED = "Vault Plugin System is not configured.";

    private VaultPluginInherit() {
    }

    /**
     * Vault base URL from HashiCorp Vault Plugin (System + folder/job merge). Does not read KV.
     */
    static String resolveVaultUrl(Run<?, ?> run, String namespace) throws AbortException {
        if (!isPluginPresent()) {
            throw new AbortException(VAULT_PLUGIN_MISSING);
        }
        if (run == null) {
            throw new AbortException("Vault Inherit requires a running build.");
        }
        try {
            ClassLoader cl = vaultApiClassLoader();
            Class<?> vaultConfigurationClass = loadClass(CLASS_VAULT_CONFIGURATION, cl);
            Class<?> vaultAccessorClass = loadClass(CLASS_VAULT_ACCESSOR, cl);
            Object stepOverrides = vaultConfigurationClass.getConstructor().newInstance();
            if (namespace != null && !namespace.isBlank()) {
                vaultConfigurationClass
                        .getMethod("setVaultNamespace", String.class)
                        .invoke(stepOverrides, namespace.trim());
            }
            vaultConfigurationClass.getMethod("setEngineVersion", Integer.class).invoke(stepOverrides, 2);
            Method pullAndMerge = vaultAccessorClass.getMethod(
                    "pullAndMergeConfiguration", Run.class, vaultConfigurationClass);
            Object configuration = pullAndMerge.invoke(null, run, stepOverrides);
            String vaultUrl = (String) vaultConfigurationClass.getMethod("getVaultUrl").invoke(configuration);
            if (vaultUrl == null || vaultUrl.isBlank()) {
                throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
            }
            return vaultUrl.trim();
        } catch (AbortException e) {
            throw e;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            LOGGER.log(Level.WARNING, "Vault Inherit: Vault Plugin classes not loadable", e);
            throw new AbortException(VAULT_PLUGIN_MISSING);
        } catch (InvocationTargetException e) {
            throw mapPluginInvoke(e);
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Vault Inherit reflection failed", e);
            throw new AbortException(
                    "Vault Inherit failed (incompatible HashiCorp Vault Plugin API). "
                            + "Update both plugins, or use Vault Manual on this step.");
        }
    }

    /**
     * Read all flat string keys from a KV v2 secret using Vault Plugin System (+ folder/job) config.
     *
     * @param mount      KV mount (e.g. {@code secret})
     * @param secretPath path within mount (e.g. {@code myapp/prod})
     * @param namespace  optional step override (Enterprise); blank keeps System/folder namespace
     * @param log        dual-sink build logger (nullable; falls back to {@code listener} println)
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> readKvV2(
            Run<?, ?> run,
            EnvVars buildEnv,
            String mount,
            String secretPath,
            String namespace,
            TaskListener listener,
            PortainerBuildLogger log) throws AbortException {
        if (!isPluginPresent()) {
            throw new AbortException(VAULT_PLUGIN_MISSING);
        }
        if (run == null) {
            throw new AbortException("Vault Inherit requires a running build.");
        }
        try {
            ClassLoader cl = vaultApiClassLoader();
            Class<?> vaultConfigurationClass = loadClass(CLASS_VAULT_CONFIGURATION, cl);
            Class<?> vaultAccessorClass = loadClass(CLASS_VAULT_ACCESSOR, cl);
            Class<?> vaultConfigClass = loadClass(CLASS_VAULT_CONFIG, cl);
            Class<?> vaultCredentialClass = loadClass(CLASS_VAULT_CREDENTIAL, cl);

            Object stepOverrides = vaultConfigurationClass.getConstructor().newInstance();
            if (namespace != null && !namespace.isBlank()) {
                vaultConfigurationClass
                        .getMethod("setVaultNamespace", String.class)
                        .invoke(stepOverrides, namespace.trim());
            }
            vaultConfigurationClass.getMethod("setEngineVersion", Integer.class).invoke(stepOverrides, 2);

            Method pullAndMerge = vaultAccessorClass.getMethod(
                    "pullAndMergeConfiguration", Run.class, vaultConfigurationClass);
            Object configuration = pullAndMerge.invoke(null, run, stepOverrides);

            String vaultUrl = (String) vaultConfigurationClass.getMethod("getVaultUrl").invoke(configuration);
            if (vaultUrl == null || vaultUrl.isBlank()) {
                throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
            }
            String credentialId =
                    (String) vaultConfigurationClass.getMethod("getVaultCredentialId").invoke(configuration);
            Object inlineCredential =
                    vaultConfigurationClass.getMethod("getVaultCredential").invoke(configuration);
            if ((credentialId == null || credentialId.isBlank()) && inlineCredential == null) {
                throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
            }

            Object vaultConfig = vaultConfigurationClass.getMethod("getVaultConfig").invoke(configuration);
            Object credential = vaultConfigurationClass.getMethod("getVaultCredential").invoke(configuration);
            if (credential == null) {
                Method retrieve = vaultAccessorClass.getMethod(
                        "retrieveVaultCredentials", Run.class, vaultConfigurationClass);
                credential = retrieve.invoke(null, run, configuration);
            }

            Object accessor = vaultAccessorClass.getConstructor().newInstance();
            vaultAccessorClass.getMethod("setConfig", vaultConfigClass).invoke(accessor, vaultConfig);
            vaultAccessorClass.getMethod("setCredential", vaultCredentialClass).invoke(accessor, credential);

            String policiesRaw =
                    (String) vaultConfigurationClass.getMethod("getPolicies").invoke(configuration);
            try {
                Method generatePolicies =
                        vaultAccessorClass.getDeclaredMethod("generatePolicies", String.class, EnvVars.class);
                generatePolicies.setAccessible(true);
                List<String> policies =
                        (List<String>) generatePolicies.invoke(
                                null, policiesRaw, buildEnv == null ? new EnvVars() : buildEnv);
                vaultAccessorClass.getMethod("setPolicies", List.class).invoke(accessor, policies);
            } catch (ReflectiveOperationException | SecurityException e) {
                LOGGER.log(Level.FINE, "Vault Inherit: could not apply policy templates", e);
            }

            int maxRetries = (Integer) vaultConfigurationClass.getMethod("getMaxRetries").invoke(configuration);
            int retryMs =
                    (Integer) vaultConfigurationClass.getMethod("getRetryIntervalMilliseconds").invoke(configuration);
            vaultAccessorClass.getMethod("setMaxRetries", int.class).invoke(accessor, maxRetries);
            vaultAccessorClass
                    .getMethod("setRetryIntervalMilliseconds", int.class)
                    .invoke(accessor, retryMs);

            vaultAccessorClass.getMethod("init").invoke(accessor);

            String prefixPath =
                    (String) vaultConfigurationClass.getMethod("getPrefixPath").invoke(configuration);
            String prefix = "";
            if (prefixPath != null && !prefixPath.isBlank()) {
                String expanded = buildEnv == null ? prefixPath.trim() : buildEnv.expand(prefixPath.trim());
                prefix = Util.ensureEndsWith(expanded, "/");
            }
            String path = prefix + mount + "/" + secretPath;

            Integer engineVersion =
                    (Integer) vaultConfigurationClass.getMethod("getEngineVersion").invoke(configuration);
            if (engineVersion == null) {
                engineVersion = 2;
            }

            if (log != null) {
                log.debug("Vault Inherit reading path=" + path
                        + " engineVersion=" + engineVersion
                        + (namespace != null && !namespace.isBlank()
                                ? " namespace=" + namespace.trim()
                                : ""));
            }

            Object response = vaultAccessorClass
                    .getMethod("read", String.class, Integer.class)
                    .invoke(accessor, path, engineVersion);

            Method responseHasErrors = vaultAccessorClass.getMethod(
                    "responseHasErrors",
                    vaultConfigurationClass,
                    java.io.PrintStream.class,
                    String.class,
                    loadClass(CLASS_LOGICAL_RESPONSE, cl));
            boolean errors = (Boolean) responseHasErrors.invoke(
                    null,
                    configuration,
                    listener == null ? System.out : listener.getLogger(),
                    path,
                    response);
            if (errors) {
                throw new AbortException(
                        "Vault Inherit: secret not found or error at path '" + path
                                + "' (see build log). Check path/mount and Vault Plugin configuration.");
            }

            Map<String, String> data =
                    (Map<String, String>) response.getClass().getMethod("getData").invoke(response);
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
        } catch (AbortException e) {
            throw e;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            LOGGER.log(Level.WARNING, "Vault Inherit: Vault Plugin classes not loadable", e);
            throw new AbortException(VAULT_PLUGIN_MISSING);
        } catch (InvocationTargetException e) {
            throw mapPluginInvoke(e);
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Vault Inherit reflection failed", e);
            throw new AbortException(
                    "Vault Inherit failed (incompatible HashiCorp Vault Plugin API). "
                            + "Update both plugins, or use Vault Manual on this step.");
        }
    }

    private static AbortException mapPluginInvoke(InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        if (msg.contains("No configuration found") || msg.contains("vault url was not configured")) {
            return new AbortException(VAULT_PLUGIN_UNCONFIGURED);
        }
        if (msg.contains("credential id was not configured") || msg.contains("CredentialsUnavailable")) {
            return new AbortException(VAULT_PLUGIN_UNCONFIGURED);
        }
        LOGGER.log(Level.WARNING, "Vault Inherit via HashiCorp Vault Plugin failed", cause);
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        return new AbortException(
                "Vault Inherit failed: " + msg
                        + " Configure HashiCorp Vault Plugin System, or use Vault Manual on this step.");
    }

    /**
     * Whether the official HashiCorp Vault Plugin is installed and active (or its API classes
     * are visible on the uber classloader).
     */
    static boolean isPluginPresent() {
        PluginWrapper wrapper = findVaultPluginWrapper();
        if (wrapper != null) {
            return wrapper.isActive();
        }
        try {
            ClassLoader cl = uberClassLoaderOrNull();
            if (cl == null) {
                return false;
            }
            loadClass(CLASS_VAULT_ACCESSOR, cl);
            loadClass(CLASS_VAULT_CONFIGURATION, cl);
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
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
            ClassLoader cl = vaultApiClassLoader();
            Class<?> globalClass = loadClass(CLASS_GLOBAL_VAULT_CONFIGURATION, cl);
            Object global = globalClass.getMethod("get").invoke(null);
            if (global == null) {
                return false;
            }
            Object configuration = globalClass.getMethod("getConfiguration").invoke(global);
            if (configuration == null) {
                return false;
            }
            String vaultUrl =
                    (String) configuration.getClass().getMethod("getVaultUrl").invoke(configuration);
            if (vaultUrl == null || vaultUrl.isBlank()) {
                return false;
            }
            String credentialId =
                    (String) configuration.getClass().getMethod("getVaultCredentialId").invoke(configuration);
            Object inlineCredential =
                    configuration.getClass().getMethod("getVaultCredential").invoke(configuration);
            return (credentialId != null && !credentialId.isBlank()) || inlineCredential != null;
        } catch (ReflectiveOperationException | NoClassDefFoundError e) {
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
        if (jenkins == null || jenkins.getPluginManager() == null) {
            return null;
        }
        PluginWrapper primary = jenkins.getPluginManager().getPlugin(VAULT_PLUGIN_SHORT_NAME);
        if (primary != null) {
            return primary;
        }
        for (String alias : VAULT_PLUGIN_SHORT_NAME_ALIASES) {
            PluginWrapper alt = jenkins.getPluginManager().getPlugin(alias);
            if (alt != null) {
                return alt;
            }
        }
        return null;
    }

    private static ClassLoader vaultApiClassLoader() {
        PluginWrapper wrapper = findVaultPluginWrapper();
        if (wrapper != null && wrapper.classLoader != null) {
            return wrapper.classLoader;
        }
        ClassLoader uber = uberClassLoaderOrNull();
        if (uber != null) {
            return uber;
        }
        return VaultPluginInherit.class.getClassLoader();
    }

    private static ClassLoader uberClassLoaderOrNull() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || jenkins.getPluginManager() == null) {
            return null;
        }
        return jenkins.getPluginManager().uberClassLoader;
    }

    private static Class<?> loadClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }
}
