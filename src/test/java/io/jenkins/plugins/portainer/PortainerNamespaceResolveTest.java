package io.jenkins.plugins.portainer;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PortainerNamespaceResolveTest {

    @Test
    public void resolveNamespace_expandsBuildEnv() {
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "apps");
        assertEquals("apps", PortainerManifestBuilder.resolveNamespace("${NAMESPACE}", env));
    }

    @Test
    public void resolveNamespace_defaultWhenBlank() {
        assertEquals(
                PortainerManifestBuilder.DEFAULT_NAMESPACE,
                PortainerManifestBuilder.resolveNamespace("  ", new EnvVars()));
    }

    @Test
    public void resolveNamespace_rejectsInvalidAfterExpand() {
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "Bad_Name");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortainerManifestBuilder.resolveNamespace("${NAMESPACE}", env));
    }
}
