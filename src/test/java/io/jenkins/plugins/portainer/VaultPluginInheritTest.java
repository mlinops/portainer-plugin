package io.jenkins.plugins.portainer;

import com.datapipe.jenkins.vault.VaultAccessor;
import com.datapipe.jenkins.vault.configuration.GlobalVaultConfiguration;
import com.datapipe.jenkins.vault.configuration.VaultConfiguration;
import com.datapipe.jenkins.vault.credentials.VaultCredential;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Vault Inherit soft-integration.
 * <p>
 * Test doubles under {@code com.datapipe.jenkins.vault.*} / {@code io.github.jopenlibs.vault.*}
 * stand in for hashicorp-vault-plugin so reflective happy-path and error mapping are exercisable
 * without that peer on the production classpath. Remaining gap: behavior quirks of the real plugin
 * (folder merge, credential stores) are not integration-tested here.
 */
@WithJenkins
class VaultPluginInheritTest {

    @BeforeEach
    void resetStubs() {
        GlobalVaultConfiguration.resetForTests();
        VaultAccessor.resetForTests();
    }

    @AfterEach
    void clearStubs() {
        GlobalVaultConfiguration.resetForTests();
        VaultAccessor.resetForTests();
    }

    @Test
    void constantsAndStubPluginPresent_publicSurface(JenkinsRule jenkins) {
        assertEquals("hashicorp-vault-plugin", VaultPluginInherit.VAULT_PLUGIN_SHORT_NAME);
        assertEquals("HashiCorp Vault Plugin is not installed.", VaultPluginInherit.VAULT_PLUGIN_MISSING);
        assertEquals("Vault Plugin System is not configured.", VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED);

        // Stubs on the test classpath make API classes loadable via uber CL
        assertTrue(VaultPluginInherit.isPluginPresent());
        assertFalse(VaultPluginInherit.isSystemConfigured());
        assertEquals("Vault Plugin is not configured.", VaultPluginInherit.inheritSummary());
        assertNull(VaultPluginInherit.findVaultPluginWrapper());
    }

    @Test
    void isSystemConfigured_nullGlobalReturnsFalse(JenkinsRule jenkins) {
        GlobalVaultConfiguration.forceGetNull = true;
        assertFalse(VaultPluginInherit.isSystemConfigured());
    }

    @Test
    void isSystemConfigured_andInheritSummary_whenGlobalReady(JenkinsRule jenkins) {
        configureGlobal("https://vault.example", "cred-1", null);
        assertTrue(VaultPluginInherit.isSystemConfigured());
        assertEquals("Vault Plugin is present and configured.", VaultPluginInherit.inheritSummary());

        GlobalVaultConfiguration.get().setConfiguration(null);
        assertFalse(VaultPluginInherit.isSystemConfigured());
    }

    @Test
    void isSystemConfigured_acceptsInlineCredential(JenkinsRule jenkins) {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(new VaultCredential("inline"));
        GlobalVaultConfiguration.get().setConfiguration(cfg);
        assertTrue(VaultPluginInherit.isSystemConfigured());
    }

    @Test
    void resolveVaultUrl_requiresRun(JenkinsRule jenkins) {
        configureGlobal("https://vault.example", "cred-1", null);
        AbortException ex = assertThrows(
                AbortException.class, () -> VaultPluginInherit.resolveVaultUrl(null, null));
        assertEquals("Vault Inherit requires a running build.", ex.getMessage());
    }

    @Test
    void resolveVaultUrl_happyPath(JenkinsRule jenkins) throws Exception {
        configureGlobal("https://vault.example", "cred-1", null);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        String url = VaultPluginInherit.resolveVaultUrl(build, "ns-step");
        assertEquals("https://vault.example", url);
    }

    @Test
    void resolveVaultUrl_unconfiguredGlobal(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        AbortException ex = assertThrows(
                AbortException.class, () -> VaultPluginInherit.resolveVaultUrl(build, null));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, ex.getMessage());
    }

    @Test
    void resolveVaultUrl_mapsPullFailure(JenkinsRule jenkins) throws Exception {
        configureGlobal("https://vault.example", "cred-1", null);
        VaultAccessor.forcePullThrows = true;
        VaultAccessor.pullException = new RuntimeException("No configuration found for folder");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        AbortException ex = assertThrows(
                AbortException.class, () -> VaultPluginInherit.resolveVaultUrl(build, null));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, ex.getMessage());
    }

    @Test
    void readKvV2_requiresRun(JenkinsRule jenkins) {
        configureGlobal("https://vault.example", "cred-1", null);
        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        null, null, "secret", "app/prod", null, TaskListener.NULL, quietLog()));
        assertEquals("Vault Inherit requires a running build.", ex.getMessage());
    }

    @Test
    void readKvV2_happyPath_withCredentialId(JenkinsRule jenkins) throws Exception {
        configureGlobal("https://vault.example", "cred-1", null);
        VaultConfiguration global = GlobalVaultConfiguration.get().getConfiguration();
        global.setPrefixPath("team/");
        global.setPolicies("pol-${JOB_NAME}");
        global.setMaxRetries(2);
        global.setRetryIntervalMilliseconds(50);

        FreeStyleProject project = jenkins.createFreeStyleProject("job-a");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        EnvVars env = new EnvVars();
        env.put("JOB_NAME", "job-a");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        PortainerBuildLogger log =
                new PortainerBuildLogger(Logger.getLogger("VaultPluginInheritTest"), listener, true);

        Map<String, String> secrets = VaultPluginInherit.readKvV2(
                build, env, "secret", "myapp/prod", "enterprise-ns", listener, log);

        assertEquals("1.2.3", secrets.get("IMAGE_TAG"));
        assertTrue(VaultAccessor.initCalled);
        assertEquals("team/secret/myapp/prod", VaultAccessor.lastReadPath);
        assertEquals(Integer.valueOf(2), VaultAccessor.lastReadEngineVersion);
        assertNotNull(VaultAccessor.lastPolicies);
        assertEquals(List.of("pol-job-a"), VaultAccessor.lastPolicies);
        String console = buf.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Vault Inherit reading path=team/secret/myapp/prod"));
        assertTrue(console.contains("namespace=enterprise-ns"));
    }

    @Test
    void readKvV2_inlineCredential_skipsRetrieve(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(new VaultCredential("inline"));
        GlobalVaultConfiguration.get().setConfiguration(cfg);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        Map<String, String> secrets = VaultPluginInherit.readKvV2(
                build, new EnvVars(), "secret", "app", null, TaskListener.NULL, null);
        assertEquals("1.2.3", secrets.get("IMAGE_TAG"));
        assertEquals("secret/app", VaultAccessor.lastReadPath);
    }

    @Test
    void readKvV2_responseErrors_abort(JenkinsRule jenkins) throws Exception {
        configureGlobal("https://vault.example", "cred-1", null);
        VaultAccessor.responseHasErrorsResult = true;
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        build, new EnvVars(), "secret", "missing", null, TaskListener.NULL, quietLog()));
        assertTrue(ex.getMessage().contains("secret not found") || ex.getMessage().contains("missing"));
    }

    @Test
    void readKvV2_missingCredential_aborts(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        // no credential id and no inline credential
        GlobalVaultConfiguration.get().setConfiguration(cfg);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        build, new EnvVars(), "secret", "app", null, TaskListener.NULL, quietLog()));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, ex.getMessage());
    }

    @Test
    void readKvV2_mapsRetrieveFailure(JenkinsRule jenkins) throws Exception {
        configureGlobal("https://vault.example", "cred-1", null);
        VaultAccessor.forceRetrieveThrows = true;
        VaultAccessor.retrieveException = new RuntimeException("credential id was not configured");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        build, new EnvVars(), "secret", "app", null, TaskListener.NULL, quietLog()));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, ex.getMessage());
    }

    @Test
    void readKvV2_mapsGenericPluginFailure(JenkinsRule jenkins) throws Exception {
        configureGlobal("https://vault.example", "cred-1", null);
        VaultAccessor.forceReadThrows = true;
        VaultAccessor.readException = new RuntimeException("upstream vault boom");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        build, new EnvVars(), "secret", "app", null, TaskListener.NULL, quietLog()));
        assertTrue(ex.getMessage().contains("Vault Inherit failed"));
        assertTrue(ex.getMessage().contains("upstream vault boom"));
    }

    @Test
    void privateConstructor_isInvocable() throws Exception {
        Constructor<VaultPluginInherit> ctor = VaultPluginInherit.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    @Test
    void mapPluginInvoke_mapsKnownMessagesAndTruncates() throws Exception {
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                mapPluginInvoke(new RuntimeException("No configuration found for folder")).getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                mapPluginInvoke(new RuntimeException("vault url was not configured")).getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                mapPluginInvoke(new RuntimeException("credential id was not configured")).getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                mapPluginInvoke(new RuntimeException("CredentialsUnavailableException")).getMessage());

        AbortException blankCause = mapPluginInvoke(new RuntimeException());
        assertTrue(blankCause.getMessage().contains("RuntimeException"));

        String longMsg = "x".repeat(350);
        AbortException truncated = mapPluginInvoke(new IllegalStateException(longMsg));
        assertTrue(truncated.getMessage().contains("…"));
        assertTrue(truncated.getMessage().startsWith("Vault Inherit failed: "));
        assertTrue(truncated.getMessage().length() < longMsg.length() + 80);

        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "mapPluginInvoke", InvocationTargetException.class);
        m.setAccessible(true);
        InvocationTargetException ite = new InvocationTargetException(null, "wrapper");
        AbortException fromNullCause = (AbortException) m.invoke(null, ite);
        assertNotNull(fromNullCause.getMessage());
    }

    @Test
    void resolveEngineVersion_nullDefaultsToTwo() throws Exception {
        FakeVaultConfiguration cfg = new FakeVaultConfiguration();
        cfg.engineVersion = null;
        assertEquals(Integer.valueOf(2), resolveEngineVersion(cfg));

        cfg.engineVersion = 1;
        assertEquals(Integer.valueOf(1), resolveEngineVersion(cfg));
    }

    @Test
    void buildSecretPath_prefixAndEnvExpand() throws Exception {
        FakeVaultConfiguration cfg = new FakeVaultConfiguration();
        cfg.prefixPath = null;
        assertEquals("secret/myapp/prod", buildSecretPath(cfg, null, "secret", "myapp/prod"));

        cfg.prefixPath = "  ";
        assertEquals("secret/myapp/prod", buildSecretPath(cfg, new EnvVars(), "secret", "myapp/prod"));

        cfg.prefixPath = "team";
        assertEquals("team/secret/app", buildSecretPath(cfg, null, "secret", "app"));

        cfg.prefixPath = "prefix/${ENV}/";
        EnvVars env = new EnvVars();
        env.put("ENV", "prod");
        assertEquals("prefix/prod/secret/db", buildSecretPath(cfg, env, "secret", "db"));
    }

    @Test
    void requireVaultUrl_blankAborts() throws Exception {
        FakeVaultConfiguration cfg = new FakeVaultConfiguration();
        cfg.vaultUrl = "  https://vault.example  ";
        assertEquals("https://vault.example", requireVaultUrl(cfg));

        cfg.vaultUrl = null;
        AbortException ex = assertThrows(AbortException.class, () -> requireVaultUrl(cfg));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, ex.getMessage());

        cfg.vaultUrl = "   ";
        AbortException blank = assertThrows(AbortException.class, () -> requireVaultUrl(cfg));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, blank.getMessage());
    }

    @Test
    void toFlatStringMap_nullEmptyBlankKeysAndNullValues() throws Exception {
        FakeLogicalResponse response = new FakeLogicalResponse();
        response.data = null;
        assertTrue(toFlatStringMap(response).isEmpty());

        response.data = Map.of();
        assertTrue(toFlatStringMap(response).isEmpty());

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("ok", "v");
        raw.put(null, "x");
        raw.put("  ", "y");
        raw.put("empty", null);
        response.data = raw;

        Map<String, String> out = toFlatStringMap(response);
        assertEquals(2, out.size());
        assertEquals("v", out.get("ok"));
        assertEquals("", out.get("empty"));
        assertFalse(out.containsKey(null));
    }

    @Test
    void logInheritRead_nullLogAndNamespaceBranches() throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "logInheritRead", PortainerBuildLogger.class, String.class, Integer.class, String.class);
        m.setAccessible(true);
        m.invoke(null, null, "secret/app", 2, null);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        PortainerBuildLogger log =
                new PortainerBuildLogger(Logger.getLogger("VaultPluginInheritTest"), listener, true);

        m.invoke(null, log, "secret/app", Integer.valueOf(2), null);
        m.invoke(null, log, "secret/app", Integer.valueOf(2), "  ");
        String withoutNs = buf.toString(StandardCharsets.UTF_8);
        assertTrue(withoutNs.contains("Vault Inherit reading path=secret/app engineVersion=2"));
        assertFalse(withoutNs.contains("namespace="));

        buf.reset();
        m.invoke(null, log, "secret/app", Integer.valueOf(2), "  ns1  ");
        String withNs = buf.toString(StandardCharsets.UTF_8);
        assertTrue(withNs.contains(
                "Vault Inherit reading path=secret/app engineVersion=2 namespace=ns1"));
    }

    @Test
    void prepareAccessor_missingMethods_mapsToIncompatibleApi(JenkinsRule jenkins) throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "prepareAccessor",
                hudson.model.Run.class,
                EnvVars.class,
                Object.class,
                Class.class,
                Class.class,
                Class.class,
                Class.class);
        m.setAccessible(true);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        InvocationTargetException ite = assertThrows(
                InvocationTargetException.class,
                () -> m.invoke(
                        null,
                        build,
                        new EnvVars(),
                        new FakeVaultConfiguration(),
                        FakeVaultConfiguration.class,
                        VaultAccessor.class,
                        io.github.jopenlibs.vault.VaultConfig.class,
                        VaultCredential.class));
        // NoSuchMethodException for getVaultCredentialId etc.
        assertTrue(ite.getCause() instanceof ReflectiveOperationException
                || ite.getCause() instanceof NoSuchMethodException);
    }

    @Test
    void applyPolicies_swallowsReflectionFailure() throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "applyPolicies",
                Object.class,
                Object.class,
                Class.class,
                Class.class,
                EnvVars.class);
        m.setAccessible(true);
        Object swallowed = assertDoesNotThrow(
                () -> m.invoke(null, new Object(), new Object(), String.class, String.class, new EnvVars()));
        assertNull(swallowed);
        assertNull(VaultAccessor.lastPolicies);
        assertTrue(VaultPluginInherit.isPluginPresent());

        VaultAccessor accessor = new VaultAccessor();
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setPolicies("pol-after-swallow");
        Object applied = assertDoesNotThrow(
                () -> m.invoke(
                        null,
                        accessor,
                        cfg,
                        VaultConfiguration.class,
                        VaultAccessor.class,
                        new EnvVars()));
        assertNull(applied);
        assertEquals(List.of("pol-after-swallow"), VaultAccessor.lastPolicies);
    }

    @Test
    void loadClassAndClassLoaders_viaReflection(JenkinsRule jenkins) throws Exception {
        Method loadClass = VaultPluginInherit.class.getDeclaredMethod(
                "loadClass", String.class, ClassLoader.class);
        loadClass.setAccessible(true);
        Class<?> self = (Class<?>) loadClass.invoke(
                null, VaultPluginInherit.class.getName(), VaultPluginInherit.class.getClassLoader());
        assertEquals(VaultPluginInherit.class, self);

        Class<?> accessor = (Class<?>) loadClass.invoke(
                null, "com.datapipe.jenkins.vault.VaultAccessor", VaultPluginInherit.class.getClassLoader());
        assertEquals(VaultAccessor.class, accessor);

        Method uber = VaultPluginInherit.class.getDeclaredMethod("uberClassLoaderOrNull");
        uber.setAccessible(true);
        assertNotNull(uber.invoke(null));

        Method apiCl = VaultPluginInherit.class.getDeclaredMethod("vaultApiClassLoader");
        apiCl.setAccessible(true);
        assertNotNull(apiCl.invoke(null));
    }

    private static void configureGlobal(String url, String credentialId, VaultCredential inline) {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl(url);
        cfg.setVaultCredentialId(credentialId);
        cfg.setVaultCredential(inline);
        GlobalVaultConfiguration.get().setConfiguration(cfg);
    }

    private static AbortException mapPluginInvoke(Throwable cause) throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "mapPluginInvoke", InvocationTargetException.class);
        m.setAccessible(true);
        return (AbortException) m.invoke(null, new InvocationTargetException(cause));
    }

    private static Integer resolveEngineVersion(FakeVaultConfiguration cfg) throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "resolveEngineVersion", Object.class, Class.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, cfg, FakeVaultConfiguration.class);
    }

    private static String buildSecretPath(
            FakeVaultConfiguration cfg, EnvVars env, String mount, String path) throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "buildSecretPath",
                Object.class,
                Class.class,
                EnvVars.class,
                String.class,
                String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, cfg, FakeVaultConfiguration.class, env, mount, path);
    }

    private static String requireVaultUrl(FakeVaultConfiguration cfg) throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod(
                "requireVaultUrl", Object.class, Class.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, cfg, FakeVaultConfiguration.class);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof AbortException ae) {
                throw ae;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toFlatStringMap(FakeLogicalResponse response) throws Exception {
        Method m = VaultPluginInherit.class.getDeclaredMethod("toFlatStringMap", Object.class);
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(null, response);
    }

    private static PortainerBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new PortainerBuildLogger(Logger.getLogger("VaultPluginInheritTest"), listener, true);
    }

    /** Stand-in for Vault Plugin configuration reflective getters (package-local helper tests). */
    public static final class FakeVaultConfiguration {
        public String vaultUrl = "https://vault.example";
        public String prefixPath;
        public Integer engineVersion = 2;

        public String getVaultUrl() {
            return vaultUrl;
        }

        public String getPrefixPath() {
            return prefixPath;
        }

        public Integer getEngineVersion() {
            return engineVersion;
        }
    }

    /** Stand-in for LogicalResponse#getData. */
    public static final class FakeLogicalResponse {
        public Map<String, String> data = new HashMap<>();

        public Map<String, String> getData() {
            return data;
        }
    }
}
