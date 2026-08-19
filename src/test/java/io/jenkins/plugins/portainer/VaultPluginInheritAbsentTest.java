package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.model.TaskListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Inherit detection when Jenkins is not running (optional Vault plugin not installed).
 */
class VaultPluginInheritAbsentTest {

    @Test
    void pluginIsMissing() {
        assertFalse(VaultPluginInherit.isPluginPresent());
        assertFalse(VaultPluginInherit.isSystemConfigured());
        assertEquals("Vault Plugin is not installed.", VaultPluginInherit.inheritSummary());
        assertEquals("hashicorp-vault-plugin", VaultPluginInherit.VAULT_PLUGIN_SHORT_NAME);

        AbortException missingUrl = assertThrows(
                AbortException.class, () -> VaultPluginInherit.resolveVaultUrl(null, null));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_MISSING, missingUrl.getMessage());

        AbortException missingRead = assertThrows(
                AbortException.class,
                () -> VaultPluginInherit.readKvV2(
                        null, null, "secret", "app", null, TaskListener.NULL, null));
        assertEquals(VaultPluginInherit.VAULT_PLUGIN_MISSING, missingRead.getMessage());
    }
}
