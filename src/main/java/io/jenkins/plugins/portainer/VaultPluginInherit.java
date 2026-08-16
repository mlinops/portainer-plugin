package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

import java.io.PrintStream;
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

    private static final String METHOD_GET_VAULT_URL = "getVaultUrl";
    private static final String METHOD_GET_VAULT_CREDENTIAL_ID = "getVaultCredentialId";
    private static final String METHOD_GET_VAULT_CREDENTIAL = "getVaultCredential";

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
            Object configuration =
                    pullAndMergeConfiguration(run, namespace, vaultConfigurationClass, vaultAccessorClass);
            return requireVaultUrl(configuration, vaultConfigurationClass);
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

            Object configuration =
                    pullAndMergeConfiguration(run, namespace, vaultConfigurationClass, vaultAccessorClass);
            requireVaultUrl(configuration, vaultConfigurationClass);

            Object accessor = prepareAccessor(
                    run,
                    buildEnv,
                    configuration,
                    vaultConfigurationClass,
                    vaultAccessorClass,
                    vaultConfigClass,
                    vaultCredentialClass);

            String path = buildSecretPath(configuration, vaultConfigurationClass, buildEnv, mount, secretPath);
            Integer engineVersion = resolveEngineVersion(configuration, vaultConfigurationClass);
            logInheritRead(log, path, engineVersion, namespace);

            Object response = readSecret(accessor, vaultAccessorClass, path, engineVersion);
            assertNoResponseErrors(
                    configuration, vaultConfigurationClass, vaultAccessorClass, cl, listener, path, response);
            return toFlatStringMap(response);
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

    private static Object pullAndMergeConfiguration(
            Run<?, ?> run,
            String namespace,
            Class<?> vaultConfigurationClass,
            Class<?> vaultAccessorClass) throws ReflectiveOperationException {
        Object stepOverrides = vaultConfigurationClass.getConstructor().newInstance();
        if (namespace != null && !namespace.isBlank()) {
            vaultConfigurationClass
                    .getMethod("setVaultNamespace", String.class)
                    .invoke(stepOverrides, namespace.trim());
        }
        vaultConfigurationClass.getMethod("setEngineVersion", Integer.class).invoke(stepOverrides, 2);
        Method pullAndMerge = vaultAccessorClass.getMethod(
                "pullAndMergeConfiguration", Run.class, vaultConfigurationClass);
        return pullAndMerge.invoke(null, run, stepOverrides);
    }

    private static String requireVaultUrl(Object configuration, Class<?> vaultConfigurationClass)
            throws ReflectiveOperationException, AbortException {
        String vaultUrl =
                (String) vaultConfigurationClass.getMethod(METHOD_GET_VAULT_URL).invoke(configuration);
        if (vaultUrl == null || vaultUrl.isBlank()) {
            throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
        }
        return vaultUrl.trim();
    }

    private static Object prepareAccessor(
            Run<?, ?> run,
            EnvVars buildEnv,
            Object configuration,
            Class<?> vaultConfigurationClass,
            Class<?> vaultAccessorClass,
            Class<?> vaultConfigClass,
            Class<?> vaultCredentialClass) throws ReflectiveOperationException, AbortException {
        Object credential =
                resolveCredential(run, configuration, vaultConfigurationClass, vaultAccessorClass);
        Object vaultConfig = vaultConfigurationClass.getMethod("getVaultConfig").invoke(configuration);

        Object accessor = vaultAccessorClass.getConstructor().newInstance();
        vaultAccessorClass.getMethod("setConfig", vaultConfigClass).invoke(accessor, vaultConfig);
        vaultAccessorClass.getMethod("setCredential", vaultCredentialClass).invoke(accessor, credential);
        applyPolicies(accessor, configuration, vaultConfigurationClass, vaultAccessorClass, buildEnv);

        int maxRetries = (Integer) vaultConfigurationClass.getMethod("getMaxRetries").invoke(configuration);
        int retryMs =
                (Integer) vaultConfigurationClass.getMethod("getRetryIntervalMilliseconds").invoke(configuration);
        vaultAccessorClass.getMethod("setMaxRetries", int.class).invoke(accessor, maxRetries);
        vaultAccessorClass
                .getMethod("setRetryIntervalMilliseconds", int.class)
                .invoke(accessor, retryMs);
        vaultAccessorClass.getMethod("init").invoke(accessor);
        return accessor;
    }

    private static Object resolveCredential(
            Run<?, ?> run,
            Object configuration,
            Class<?> vaultConfigurationClass,
            Class<?> vaultAccessorClass) throws ReflectiveOperationException, AbortException {
        String credentialId =
                (String) vaultConfigurationClass.getMethod(METHOD_GET_VAULT_CREDENTIAL_ID).invoke(configuration);
        Object credential =
                vaultConfigurationClass.getMethod(METHOD_GET_VAULT_CREDENTIAL).invoke(configuration);
        if ((credentialId == null || credentialId.isBlank()) && credential == null) {
            throw new AbortException(VAULT_PLUGIN_UNCONFIGURED);
        }
        if (credential == null) {
            Method retrieve = vaultAccessorClass.getMethod(
                    "retrieveVaultCredentials", Run.class, vaultConfigurationClass);
            credential = retrieve.invoke(null, run, configuration);
        }
        return credential;
    }

    @SuppressWarnings("unchecked")
    private static void applyPolicies(
            Object accessor,
            Object configuration,
            Class<?> vaultConfigurationClass,
            Class<?> vaultAccessorClass,
            EnvVars buildEnv) {
        try {
            String policiesRaw =
                    (String) vaultConfigurationClass.getMethod("getPolicies").invoke(configuration);
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
    }

    private static String buildSecretPath(
            Object configuration,
            Class<?> vaultConfigurationClass,
            EnvVars buildEnv,
            String mount,
            String secretPath) throws ReflectiveOperationException {
        String prefixPath =
                (String) vaultConfigurationClass.getMethod("getPrefixPath").invoke(configuration);
        String prefix = "";
        if (prefixPath != null && !prefixPath.isBlank()) {
            String expanded = buildEnv == null ? prefixPath.trim() : buildEnv.expand(prefixPath.trim());
            prefix = Util.ensureEndsWith(expanded, VAULT_PATH_SEP);
        }
        return prefix + mount + VAULT_PATH_SEP + secretPath;
    }

    private static Integer resolveEngineVersion(Object configuration, Class<?> vaultConfigurationClass)
            throws ReflectiveOperationException {
        Integer engineVersion =
                (Integer) vaultConfigurationClass.getMethod("getEngineVersion").invoke(configuration);
        return engineVersion == null ? 2 : engineVersion;
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

    private static Object readSecret(
            Object accessor, Class<?> vaultAccessorClass, String path, Integer engineVersion)
            throws ReflectiveOperationException {
        return vaultAccessorClass
                .getMethod("read", String.class, Integer.class)
                .invoke(accessor, path, engineVersion);
    }

    private static void assertNoResponseErrors(
            Object configuration,
            Class<?> vaultConfigurationClass,
            Class<?> vaultAccessorClass,
            ClassLoader cl,
            TaskListener listener,
            String path,
            Object response) throws ReflectiveOperationException, AbortException {
        Method responseHasErrors = vaultAccessorClass.getMethod(
                "responseHasErrors",
                vaultConfigurationClass,
                PrintStream.class,
                String.class,
                loadClass(CLASS_LOGICAL_RESPONSE, cl));
        PrintStream out = listener == null ? TaskListener.NULL.getLogger() : listener.getLogger();
        boolean errors = (Boolean) responseHasErrors.invoke(null, configuration, out, path, response);
        if (errors) {
            throw new AbortException(
                    "Vault Inherit: secret not found or error at path '" + path
                            + "' (see build log). Check path/mount and Vault Plugin configuration.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toFlatStringMap(Object response)
            throws ReflectiveOperationException {
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
                    (String) configuration.getClass().getMethod(METHOD_GET_VAULT_URL).invoke(configuration);
            if (vaultUrl == null || vaultUrl.isBlank()) {
                return false;
            }
            String credentialId = (String) configuration
                    .getClass()
                    .getMethod(METHOD_GET_VAULT_CREDENTIAL_ID)
                    .invoke(configuration);
            Object inlineCredential = configuration
                    .getClass()
                    .getMethod(METHOD_GET_VAULT_CREDENTIAL)
                    .invoke(configuration);
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
        if (jenkins == null) {
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
        if (jenkins == null) {
            return null;
        }
        return jenkins.getPluginManager().uberClassLoader;
    }

    private static Class<?> loadClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }
}
