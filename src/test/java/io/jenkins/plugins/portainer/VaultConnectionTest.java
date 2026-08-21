package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class VaultConnectionTest {

    @Test
    public void fromLegacy_stack_defaultsNone() {
        VaultConnection vault = VaultConnection.fromLegacy(
                null, null, null, null, null, null, null, false);
        assertInstanceOf(VaultNone.class, vault);
        assertEquals(ConnectionMode.NONE, vault.getMode());
    }

    @Test
    public void fromLegacy_stack_explicitNone_ignoresSiblings() {
        VaultConnection vault = VaultConnection.fromLegacy(
                "none",
                "https://vault.example:8200",
                "vault-approle",
                "myapp/prod",
                "secret",
                null,
                null,
                false);
        assertInstanceOf(VaultNone.class, vault);
    }

    @Test
    public void fromLegacy_stack_pathOnly_isInherit() {
        VaultConnection vault = VaultConnection.fromLegacy(
                null, null, null, "myapp/prod", "secret", null, null, false);
        assertInstanceOf(VaultInherit.class, vault);
        assertEquals("myapp/prod", vault.getVaultPath());
        assertEquals("secret", vault.getVaultMount());
    }

    @Test
    public void fromLegacy_stack_urlAndCred_isManual() {
        VaultConnection vault = VaultConnection.fromLegacy(
                null,
                "https://vault.example:8200",
                "vault-approle",
                "myapp/prod",
                null,
                null,
                null,
                false);
        assertInstanceOf(VaultManual.class, vault);
        assertEquals("https://vault.example:8200", vault.getVaultUrl());
        assertEquals("vault-approle", vault.getVaultAppRoleCredentialsId());
        assertEquals("myapp/prod", vault.getVaultPath());
    }

    @Test
    public void fromLegacy_secret_noneBecomesInherit() {
        VaultConnection vault = VaultConnection.fromLegacy(
                "none", null, null, "apps/demo", null, null, null, true);
        assertInstanceOf(VaultInherit.class, vault);
        assertEquals("apps/demo", vault.getVaultPath());
    }

    @Test
    public void fromLegacy_secret_emptyDefaultsInherit() {
        VaultConnection vault = VaultConnection.fromLegacy(
                null, null, null, null, null, null, null, true);
        assertInstanceOf(VaultInherit.class, vault);
    }
}
