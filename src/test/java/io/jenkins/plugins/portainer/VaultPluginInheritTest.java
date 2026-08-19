package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
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
import io.github.jopenlibs.vault.response.LogicalResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * HashiCorp Vault Plugin Inherit — optional plugin present in JenkinsRule tests.
 */
@WithJenkins
class VaultPluginInheritTest {

    @AfterEach
    void clearGlobalVault() {
        if (VaultPluginInherit.isPluginPresent()) {
            GlobalVaultConfiguration.get().setConfiguration(null);
        }
    }

    @Test
    void pluginPresent_unconfiguredUntilGlobalSet(JenkinsRule jenkins) {
        assertTrue(VaultPluginInherit.isPluginPresent());
        assertNotNull(VaultPluginInherit.findVaultPluginWrapper());
        assertFalse(VaultPluginInherit.isSystemConfigured());
        assertEquals("Vault Plugin is not configured.", VaultPluginInherit.inheritSummary());
    }

    @Test
    void isSystemConfigured_urlWithoutCredentials_isFalse(JenkinsRule jenkins) {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        GlobalVaultConfiguration.get().setConfiguration(cfg);
        assertFalse(VaultPluginInherit.isSystemConfigured());
    }

    @Test
    void isSystemConfigured_whenGlobalReady(JenkinsRule jenkins) {
        configureGlobal("https://vault.example", "cred-1", null);
        assertTrue(VaultPluginInherit.isSystemConfigured());
        assertEquals("Vault Plugin is present and configured.", VaultPluginInherit.inheritSummary());
    }

    @Test
    void isSystemConfigured_acceptsInlineCredential(JenkinsRule jenkins) {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(mock(VaultCredential.class));
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
    void readKvV2_requiresRun(JenkinsRule jenkins) {
        configureGlobal("https://vault.example", "cred-1", null);
        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        null, null, "secret", "app/prod", null, TaskListener.NULL, quietLog()));
        assertEquals("Vault Inherit requires a running build.", ex.getMessage());
    }

    @Test
    void readKvV2_happyPath_inlineCredential(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(mock(VaultCredential.class));
        cfg.setPrefixPath("team/");
        cfg.setPolicies("pol-${JOB_NAME}");
        GlobalVaultConfiguration.get().setConfiguration(cfg);

        FreeStyleProject project = jenkins.createFreeStyleProject("job-a");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        EnvVars env = new EnvVars();
        env.put("JOB_NAME", "job-a");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        PortainerBuildLogger log =
                new PortainerBuildLogger(Logger.getLogger("VaultPluginInheritTest"), listener, true);

        LogicalResponse response = mock(LogicalResponse.class);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("IMAGE_TAG", "1.2.3");
        data.put("  ", "skip");
        data.put("empty", null);
        when(response.getData()).thenReturn(data);

        AtomicReference<String> readPath = new AtomicReference<>();
        AtomicReference<Integer> readEngine = new AtomicReference<>();

        try (MockedConstruction<VaultAccessor> constructed = mockConstruction(VaultAccessor.class, (mock, ctx) -> {
            when(mock.init()).thenReturn(mock);
            when(mock.read(anyString(), any())).thenAnswer(invocation -> {
                readPath.set(invocation.getArgument(0));
                readEngine.set(invocation.getArgument(1));
                return response;
            });
        })) {
            Map<String, String> secrets = VaultPluginInherit.readKvV2(
                    build, env, "secret", "myapp/prod", "enterprise-ns", listener, log);
            assertEquals("1.2.3", secrets.get("IMAGE_TAG"));
            assertEquals("", secrets.get("empty"));
            assertFalse(secrets.containsKey("  "));
            assertEquals(1, constructed.constructed().size());
        }

        assertEquals("team/secret/myapp/prod", readPath.get());
        assertEquals(Integer.valueOf(2), readEngine.get());
        String console = buf.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Vault Inherit reading path=team/secret/myapp/prod"));
        assertTrue(console.contains("namespace=enterprise-ns"));
    }

    @Test
    void readKvV2_nullLog_emptyData(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(mock(VaultCredential.class));
        GlobalVaultConfiguration.get().setConfiguration(cfg);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        LogicalResponse response = mock(LogicalResponse.class);
        when(response.getData()).thenReturn(null);

        try (MockedConstruction<VaultAccessor> constructed = mockConstruction(VaultAccessor.class, (mock, ctx) -> {
            when(mock.init()).thenReturn(mock);
            when(mock.read(anyString(), any())).thenReturn(response);
        })) {
            Map<String, String> secrets = VaultPluginInherit.readKvV2(
                    build, new EnvVars(), "secret", "app", "  ", TaskListener.NULL, null);
            assertTrue(secrets.isEmpty());
            assertEquals(1, constructed.constructed().size());
        }
    }

    @Test
    void readKvV2_missingCredential_aborts(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
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
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        try (MockedStatic<VaultAccessor> vault = mockStatic(VaultAccessor.class, CALLS_REAL_METHODS)) {
            vault.when(() -> VaultAccessor.retrieveVaultCredentials(any(), any()))
                    .thenThrow(new CredentialsUnavailableException("cred-1"));
            AbortException ex = assertThrows(
                    AbortException.class,
                    () -> VaultPluginInherit.readKvV2(
                            build, new EnvVars(), "secret", "app", null, TaskListener.NULL, quietLog()));
            assertEquals(VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED, ex.getMessage());
        }
    }

    @Test
    void readKvV2_responseErrors_abort(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(mock(VaultCredential.class));
        GlobalVaultConfiguration.get().setConfiguration(cfg);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        LogicalResponse response = mock(LogicalResponse.class);
        when(response.getData()).thenReturn(Map.of());

        try (MockedStatic<VaultAccessor> vault = mockStatic(VaultAccessor.class, CALLS_REAL_METHODS);
                MockedConstruction<VaultAccessor> constructed = mockConstruction(VaultAccessor.class, (mock, ctx) -> {
                    when(mock.init()).thenReturn(mock);
                    when(mock.read(anyString(), any())).thenReturn(response);
                })) {
            vault.when(() -> VaultAccessor.responseHasErrors(any(), any(), anyString(), any()))
                    .thenReturn(true);
            AbortException ex = assertThrows(
                    AbortException.class,
                    () -> VaultPluginInherit.readKvV2(
                            build, new EnvVars(), "secret", "missing", null, TaskListener.NULL, quietLog()));
            assertTrue(ex.getMessage().contains("secret not found") || ex.getMessage().contains("missing"));
            assertEquals(1, constructed.constructed().size());
        }
    }

    @Test
    void readKvV2_mapsGenericPluginFailure(JenkinsRule jenkins) throws Exception {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl("https://vault.example");
        cfg.setVaultCredential(mock(VaultCredential.class));
        GlobalVaultConfiguration.get().setConfiguration(cfg);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        try (MockedConstruction<VaultAccessor> constructed = mockConstruction(VaultAccessor.class, (mock, ctx) -> {
            when(mock.init()).thenReturn(mock);
            when(mock.read(anyString(), any())).thenThrow(new RuntimeException("upstream vault boom"));
        })) {
            AbortException ex = assertThrows(
                    AbortException.class,
                    () -> VaultPluginInherit.readKvV2(
                            build, new EnvVars(), "secret", "app", null, TaskListener.NULL, quietLog()));
            assertTrue(ex.getMessage().contains("Vault Inherit failed"));
            assertTrue(ex.getMessage().contains("upstream vault boom"));
            assertEquals(1, constructed.constructed().size());
        }
    }

    @Test
    void mapPluginFailure_mapsKnownMessagesAndTruncates() {
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                VaultPluginInherit.mapPluginFailure(new RuntimeException("No configuration found for folder"))
                        .getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                VaultPluginInherit.mapPluginFailure(new RuntimeException("vault url was not configured"))
                        .getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                VaultPluginInherit.mapPluginFailure(new RuntimeException("credential id was not configured"))
                        .getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                VaultPluginInherit.mapPluginFailure(new RuntimeException("CredentialsUnavailableException"))
                        .getMessage());
        assertEquals(
                VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED,
                VaultPluginInherit.mapPluginFailure(new CredentialsUnavailableException("id")).getMessage());

        AbortException blankCause = VaultPluginInherit.mapPluginFailure(new RuntimeException());
        assertTrue(blankCause.getMessage().contains("RuntimeException"));

        AbortException nullCause = VaultPluginInherit.mapPluginFailure(null);
        assertNotNull(nullCause.getMessage());

        String longMsg = "x".repeat(350);
        AbortException truncated = VaultPluginInherit.mapPluginFailure(new IllegalStateException(longMsg));
        assertTrue(truncated.getMessage().contains("…"));
        assertTrue(truncated.getMessage().startsWith("Vault Inherit failed: "));
        assertTrue(truncated.getMessage().length() < longMsg.length() + 80);
    }

    private static void configureGlobal(String url, String credentialId, VaultCredential inline) {
        VaultConfiguration cfg = new VaultConfiguration();
        cfg.setVaultUrl(url);
        cfg.setVaultCredentialId(credentialId);
        cfg.setVaultCredential(inline);
        GlobalVaultConfiguration.get().setConfiguration(cfg);
    }

    private static PortainerBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new PortainerBuildLogger(Logger.getLogger("VaultPluginInheritTest"), listener, true);
    }
}
