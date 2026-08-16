package io.jenkins.plugins.portainer;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void validateNamespace_andRequireLooksLikeYaml() {
        assertEquals("Namespace is required.", PortainerManifestBuilder.validateNamespace(null));
        assertEquals("Namespace is required.", PortainerManifestBuilder.validateNamespace("  "));
        assertTrue(PortainerManifestBuilder.validateNamespace("Bad_Name").contains("DNS-1123"));
        assertNull(PortainerManifestBuilder.validateNamespace("apps"));
        PortainerManifestBuilder.requireValidNamespace("default");
        assertThrows(IllegalArgumentException.class, () -> PortainerManifestBuilder.requireValidNamespace("BAD"));

        PortainerManifestBuilder.requireLooksLikeYaml("apiVersion: v1\nkind: Pod\n");
        assertThrows(IllegalArgumentException.class, () -> PortainerManifestBuilder.requireLooksLikeYaml(""));
        assertThrows(IllegalArgumentException.class, () -> PortainerManifestBuilder.requireLooksLikeYaml("not yaml"));
    }

    @Test
    public void resolveNamespace_nullEnv_andBlankExpandFallsBack() {
        assertEquals(
                PortainerManifestBuilder.DEFAULT_NAMESPACE,
                PortainerManifestBuilder.resolveNamespace(null, null));
        EnvVars env = new EnvVars();
        env.put("EMPTY", "");
        assertEquals(
                PortainerManifestBuilder.DEFAULT_NAMESPACE,
                PortainerManifestBuilder.resolveNamespace("${EMPTY}", env));
    }
}
