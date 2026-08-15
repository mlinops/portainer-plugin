package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.model.TaskListener;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for PORT-15 Inherit / Manual connection modes (no Maven Vault Plugin dep).
 */
public class ConnectionModeTest {

    @Test
    public void normalize_defaultsToInherit() {
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize(null, ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize(" ", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize("INHERIT", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.MANUAL, ConnectionMode.normalize("manual", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.NONE, ConnectionMode.normalize("none", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.NONE, ConnectionMode.normalize("off", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.NONE, ConnectionMode.normalize("disabled", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize("other", ConnectionMode.INHERIT));
    }

    @Test
    public void normalize_parsesRadioBlockJsonString() {
        assertEquals(
                ConnectionMode.MANUAL,
                ConnectionMode.normalize("{\"value\":\"manual\"}", ConnectionMode.INHERIT));
        assertEquals(
                ConnectionMode.INHERIT,
                ConnectionMode.normalize("{\"value\":\"inherit\"}", ConnectionMode.INHERIT));
        assertEquals(
                ConnectionMode.NONE,
                ConnectionMode.normalize("{\"value\":\"none\"}", ConnectionMode.INHERIT));
    }

    @Test
    public void isNone_and_isInherit_areStrict() {
        assertTrue(ConnectionMode.isNone(ConnectionMode.NONE));
        assertFalse(ConnectionMode.isInherit(ConnectionMode.NONE));
        assertFalse(ConnectionMode.isManual(ConnectionMode.NONE));
        assertTrue(ConnectionMode.isInherit(ConnectionMode.INHERIT));
        assertFalse(ConnectionMode.isNone(ConnectionMode.INHERIT));
        assertTrue(ConnectionMode.isManual(ConnectionMode.MANUAL));
        assertFalse(ConnectionMode.isInherit(ConnectionMode.MANUAL));
    }

    @Test
    public void flattenRadioBlock_hoistsModeAndManualFields() {
        JSONObject form = new JSONObject();
        JSONObject inherit = new JSONObject();
        inherit.put("value", "inherit");
        form.put("portainerConnectionMode", inherit);

        JSONObject vaultManual = new JSONObject();
        vaultManual.put("value", "manual");
        vaultManual.put("vaultUrl", "https://vault.example:8200");
        vaultManual.put("vaultAppRoleCredentialsId", "approle");
        vaultManual.put("vaultPath", "myapp/prod");
        form.put("vaultConnectionMode", vaultManual);

        ConnectionMode.flattenRadioBlock(
                form, "portainerConnectionMode", "portainerUrl", "portainerCredentialsId");
        ConnectionMode.flattenRadioBlock(
                form,
                "vaultConnectionMode",
                "vaultUrl",
                "vaultAppRoleCredentialsId",
                "vaultPath",
                "vaultMount",
                "vaultNamespace",
                "vaultVersion");

        assertEquals(ConnectionMode.INHERIT, form.getString("portainerConnectionMode"));
        assertFalse(form.has("portainerUrl"));
        assertEquals(ConnectionMode.MANUAL, form.getString("vaultConnectionMode"));
        assertEquals("https://vault.example:8200", form.getString("vaultUrl"));
        assertEquals("approle", form.getString("vaultAppRoleCredentialsId"));
        assertEquals("myapp/prod", form.getString("vaultPath"));
    }

    @Test
    public void flattenRadioBlock_vaultNone() {
        JSONObject form = new JSONObject();
        JSONObject none = new JSONObject();
        none.put("value", "none");
        form.put("vaultConnectionMode", none);
        ConnectionMode.flattenRadioBlock(
                form, "vaultConnectionMode", "vaultUrl", "vaultAppRoleCredentialsId", "vaultPath");
        assertEquals(ConnectionMode.NONE, form.getString("vaultConnectionMode"));
        assertFalse(form.has("vaultPath"));
    }

    @Test
    public void flattenRadioBlock_leavesPipelineStringModes() {
        JSONObject form = new JSONObject();
        form.put("portainerConnectionMode", "manual");
        form.put("portainerUrl", "https://portainer.example:9443");
        ConnectionMode.flattenRadioBlock(
                form, "portainerConnectionMode", "portainerUrl", "portainerCredentialsId");
        assertEquals("manual", form.getString("portainerConnectionMode"));
        assertEquals("https://portainer.example:9443", form.getString("portainerUrl"));
    }

    @Test
    public void stackSource_normalizeAndFlatten() {
        assertEquals(StackSource.REPOSITORY, StackSource.normalize(null));
        assertEquals(StackSource.YAML, StackSource.normalize("yaml"));
        assertEquals(StackSource.REPOSITORY, StackSource.normalize("git"));
        assertEquals(StackSource.YAML, StackSource.normalize("manual"));

        JSONObject form = new JSONObject();
        JSONObject source = new JSONObject();
        source.put("value", "yaml");
        source.put("stackFileContent", "services:\n  web:\n    image: nginx:alpine\n");
        form.put("stackSource", source);
        StackSource.flattenRadioBlock(form);
        assertEquals(StackSource.YAML, form.getString("stackSource"));
        assertTrue(form.getString("stackFileContent").contains("nginx"));
    }

    @Test
    public void setters_acceptPipelineString() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");

        step.setPortainerConnectionMode(PortainerStackBuilder.MODE_MANUAL);
        step.setPortainerUrl("https://portainer.example:9443");
        step.setPortainerCredentialsId("portainer-key");
        assertEquals(PortainerStackBuilder.MODE_MANUAL, step.getPortainerConnectionMode());
        assertEquals("https://portainer.example:9443", step.getPortainerUrl());
        assertEquals("portainer-key", step.getPortainerCredentialsId());

        step.setPortainerConnectionMode(PortainerStackBuilder.MODE_INHERIT);
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
        assertEquals("https://portainer.example:9443", step.getPortainerUrl());

        step.setVaultConnectionMode(PortainerStackBuilder.MODE_MANUAL);
        step.setVaultUrl("https://vault.example:8200");
        step.setVaultAppRoleCredentialsId("vault-approle");
        assertEquals(PortainerStackBuilder.MODE_MANUAL, step.getVaultConnectionMode());
        assertEquals("https://vault.example:8200", step.getVaultUrl());
        assertEquals("vault-approle", step.getVaultAppRoleCredentialsId());
    }

    @Test
    public void portainerDefaultsToInherit() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getPortainerConnectionMode());
        assertTrue(ConnectionMode.isInherit(step.getPortainerConnectionMode()));
    }

    @Test
    public void vaultDefaultsToNone_whenEmpty() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        assertEquals(PortainerStackBuilder.MODE_NONE, step.getVaultConnectionMode());
        assertTrue(ConnectionMode.isNone(step.getVaultConnectionMode()));
    }

    @Test
    public void vaultMigratesPathOnly_toInherit() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultPath("myapp/prod");
        // no vaultConnectionMode set → pre-PORT-23 Inherit migration
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getVaultConnectionMode());
    }

    @Test
    public void vaultMigratesLegacyManualFields_toManual() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultUrl("https://vault.example:8200");
        step.setVaultAppRoleCredentialsId("vault-approle");
        step.setVaultPath("myapp/prod");
        // no vaultConnectionMode set → migration
        assertEquals(PortainerStackBuilder.MODE_MANUAL, step.getVaultConnectionMode());
    }

    @Test
    public void vaultExplicitNone_disablesOverlay() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_NONE);
        step.setVaultPath("myapp/prod");
        step.setVaultUrl("https://vault.example:8200");
        step.setVaultAppRoleCredentialsId("vault-approle");
        assertEquals(PortainerStackBuilder.MODE_NONE, step.getVaultConnectionMode());
        assertTrue(ConnectionMode.isNone(step.getVaultConnectionMode()));
        assertEquals("off", step.vaultModeLabelForLog(null));
    }

    @Test
    public void vaultExplicitInherit_overridesMigrationHint() {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultUrl("https://vault.example:8200");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_INHERIT);
        assertEquals(PortainerStackBuilder.MODE_INHERIT, step.getVaultConnectionMode());
    }

    @Test
    public void resolveVaultOverlay_none_ignoresPathAndManualFields() throws Exception {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_NONE);
        step.setVaultPath("myapp/prod");
        step.setVaultUrl("https://vault.example:8200");
        step.setVaultAppRoleCredentialsId("vault-approle");
        PortainerBuildLogger log = new PortainerBuildLogger(
                java.util.logging.Logger.getLogger("test"), TaskListener.NULL, false);
        assertEquals(
                null,
                step.resolveVaultOverlay(null, new hudson.EnvVars(), null, null, null, log));
    }

    @Test
    public void resolveVaultOverlay_inheritEmptyPath_softSkips() throws Exception {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVaultConnectionMode(PortainerStackBuilder.MODE_INHERIT);
        PortainerBuildLogger log = new PortainerBuildLogger(
                java.util.logging.Logger.getLogger("test"), TaskListener.NULL, false);
        assertEquals(
                null,
                step.resolveVaultOverlay(null, new hudson.EnvVars(), null, null, null, log));
    }

    @Test
    public void resolveConnection_manual_requiresUrlAndCredentials() {
        AbortException ex = assertThrows(
                AbortException.class,
                () -> PortainerConnections.resolve(
                        null, PortainerStackBuilder.MODE_MANUAL, "https://portainer.example", null));
        assertTrue(ex.getMessage().contains("Manual"));
        assertTrue(ex.getMessage().contains("Inherit"));
    }

    @Test
    public void resolveConnection_inherit_failsWhenSystemEmpty() {
        AbortException ex = assertThrows(
                AbortException.class,
                () -> PortainerConnections.resolve(
                        null, PortainerStackBuilder.MODE_INHERIT, null, null));
        assertTrue(ex.getMessage().contains("Portainer is not configured"));
        assertTrue(ex.getMessage().contains("Manual"));
    }

    @Test
    public void vaultPluginInherit_missingPlugin_clearMessage() {
        // Soft dep: no hashicorp-vault-plugin on PluginManager / uber CL in this module.
        assertFalse(VaultPluginInherit.isPluginPresent());
        assertFalse(VaultPluginInherit.isSystemConfigured());
        assertEquals("hashicorp-vault-plugin", VaultPluginInherit.VAULT_PLUGIN_SHORT_NAME);
        AbortException ex = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        null,
                        null,
                        "secret",
                        "myapp/prod",
                        null,
                        TaskListener.NULL,
                        null));
        assertTrue(ex.getMessage().contains("HashiCorp Vault Plugin is not installed"));
        assertFalse(ex.getMessage().contains("Manual"));
    }

    @Test
    public void vaultPluginInherit_summary_whenMissing() {
        String summary = VaultPluginInherit.inheritSummary();
        assertEquals("Vault Plugin is not installed.", summary);
    }

    @Test
    public void vaultPluginMissing_messageConstant() {
        assertEquals("HashiCorp Vault Plugin is not installed.", VaultPluginInherit.VAULT_PLUGIN_MISSING);
        assertEquals("Vault Plugin System is not configured.", VaultPluginInherit.VAULT_PLUGIN_UNCONFIGURED);
    }

    private static PortainerStackBuilder repoStack(
            String endpointId, String stackType, String stackName, String repositoryUrl) {
        PortainerStackBuilder step = new PortainerStackBuilder(endpointId, stackType, stackName);
        step.setRepositoryUrl(repositoryUrl);
        return step;
    }
}
