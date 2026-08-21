package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.model.TaskListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Inherit / Manual / None connection modes.
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
        assertEquals(ConnectionMode.NONE, ConnectionMode.normalize("disconnected", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize("other", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.MANUAL, ConnectionMode.normalize("unknown", ConnectionMode.MANUAL));
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
    public void stackSource_normalize() {
        assertEquals(StackSource.REPOSITORY, StackSource.normalize(null));
        assertEquals(StackSource.YAML, StackSource.normalize("yaml"));
        assertEquals(StackSource.REPOSITORY, StackSource.normalize("git"));
        assertEquals(StackSource.YAML, StackSource.normalize("manual"));
        assertTrue(StackSource.isYaml("yaml"));
        assertTrue(StackSource.isRepository("repository"));
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

        VaultManual vault = new VaultManual("https://vault.example:8200", "vault-approle");
        step.setVault(vault);
        assertInstanceOf(VaultManual.class, step.getVault());
        assertEquals("https://vault.example:8200", step.getVault().getVaultUrl());
        assertEquals("vault-approle", step.getVault().getVaultAppRoleCredentialsId());
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
        assertInstanceOf(VaultNone.class, step.getVault());
        assertTrue(step.getVault().isNone());
    }

    @Test
    public void resolveVaultOverlay_none_isEmpty() throws Exception {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVault(new VaultNone());
        PortainerBuildLogger log = new PortainerBuildLogger(
                java.util.logging.Logger.getLogger("test"), TaskListener.NULL, false);
        assertTrue(step.resolveVaultOverlay(null, new hudson.EnvVars(), null, null, null, log).isEmpty());
        assertEquals("off", step.vaultModeLabelForLog(null));
    }

    @Test
    public void resolveVaultOverlay_inheritEmptyPath_softSkips() throws Exception {
        PortainerStackBuilder step = repoStack("1", "compose", "demo", "https://gitlab.example/group/stack.git");
        step.setVault(new VaultInherit());
        PortainerBuildLogger log = new PortainerBuildLogger(
                java.util.logging.Logger.getLogger("test"), TaskListener.NULL, false);
        assertTrue(step.resolveVaultOverlay(null, new hudson.EnvVars(), null, null, null, log).isEmpty());
        assertEquals("off", step.vaultModeLabelForLog(null));
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

    private static PortainerStackBuilder repoStack(
            String endpointId, String stackType, String stackName, String repositoryUrl) {
        PortainerStackBuilder step = new PortainerStackBuilder(endpointId, stackType, stackName);
        step.setRepositoryUrl(repositoryUrl);
        return step;
    }
}
