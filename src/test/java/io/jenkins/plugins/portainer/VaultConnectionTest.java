package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class VaultConnectionTest {

    @Test
    void fromLegacy_stack_defaultsNone() {
        VaultConnection vault = VaultConnection.fromLegacy(leftover(), false);
        assertInstanceOf(VaultNone.class, vault);
        assertEquals(ConnectionMode.NONE, vault.getMode());
    }

    @Test
    void fromLegacy_stack_explicitNone_ignoresSiblings() {
        VaultConnection vault = VaultConnection.fromLegacy(
                leftover(
                        "none",
                        "https://vault.example:8200",
                        "vault-approle",
                        "myapp/prod",
                        "secret",
                        null,
                        null),
                false);
        assertInstanceOf(VaultNone.class, vault);
    }

    @Test
    void fromLegacy_stack_pathOnly_isInherit() {
        VaultConnection vault = VaultConnection.fromLegacy(
                leftover(null, null, null, "myapp/prod", "secret", null, null), false);
        assertInstanceOf(VaultInherit.class, vault);
        assertEquals("myapp/prod", vault.getVaultPath());
        assertEquals("secret", vault.getVaultMount());
    }

    @Test
    void fromLegacy_stack_urlAndCred_isManual() {
        VaultConnection vault = VaultConnection.fromLegacy(
                leftover(
                        null,
                        "https://vault.example:8200",
                        "vault-approle",
                        "myapp/prod",
                        null,
                        null,
                        null),
                false);
        assertInstanceOf(VaultManual.class, vault);
        assertEquals("https://vault.example:8200", vault.getVaultUrl());
        assertEquals("vault-approle", vault.getVaultAppRoleCredentialsId());
        assertEquals("myapp/prod", vault.getVaultPath());
    }

    @Test
    void fromLegacy_secret_noneBecomesInherit() {
        VaultConnection vault = VaultConnection.fromLegacy(
                leftover("none", null, null, "apps/demo", null, null, null), true);
        assertInstanceOf(VaultInherit.class, vault);
        assertEquals("apps/demo", vault.getVaultPath());
    }

    @Test
    void fromLegacy_secret_emptyDefaultsInherit() {
        VaultConnection vault = VaultConnection.fromLegacy(leftover(), true);
        assertInstanceOf(VaultInherit.class, vault);
    }

    @Test
    void inherit_modeIsInherit() {
        VaultInherit inherit = new VaultInherit();
        assertEquals(ConnectionMode.INHERIT, inherit.getMode());
        assertFalse(inherit.isNone());
    }

    @Test
    void migrate_null_usesFromLegacy() {
        assertInstanceOf(VaultNone.class, VaultConnection.migrate(null, leftover(), false));
        assertInstanceOf(VaultInherit.class, VaultConnection.migrate(null, leftover(), true));
    }

    @Test
    void migrate_secret_persistedNone_becomesInherit() {
        assertInstanceOf(
                VaultInherit.class, VaultConnection.migrate(new VaultNone(), leftover(), true));
    }

    @Test
    void migrate_stack_persistedNone_staysNone() {
        assertInstanceOf(VaultNone.class, VaultConnection.migrate(new VaultNone(), leftover(), false));
    }

    @Test
    void migrate_keepsExistingManual() {
        VaultManual manual = new VaultManual("https://vault.example:8200", "vault-approle");
        VaultConnection migrated = VaultConnection.migrate(
                manual, leftover("none", null, null, null, null, null, null), true);
        assertInstanceOf(VaultManual.class, migrated);
        assertEquals("https://vault.example:8200", migrated.getVaultUrl());
    }

    private static VaultConnection.Leftover leftover() {
        return leftover(null, null, null, null, null, null, null);
    }

    private static VaultConnection.Leftover leftover(
            String mode,
            String url,
            String credentialsId,
            String path,
            String mount,
            String namespace,
            String version) {
        return new VaultConnection.Leftover(mode, url, credentialsId, path, mount, namespace, version);
    }
}
