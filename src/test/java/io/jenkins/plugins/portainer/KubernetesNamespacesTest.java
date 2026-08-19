package io.jenkins.plugins.portainer;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KubernetesNamespacesTest {

    @Test
    public void resolve_expandsBuildEnv() {
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "apps");
        assertEquals("apps", KubernetesNamespaces.resolve("${NAMESPACE}", env));
    }

    @Test
    public void resolve_defaultWhenBlank() {
        assertEquals(KubernetesNamespaces.DEFAULT, KubernetesNamespaces.resolve("  ", new EnvVars()));
        assertEquals(KubernetesNamespaces.DEFAULT, KubernetesNamespaces.resolve(null, null));
    }

    @Test
    public void validate_dns1123() {
        assertEquals("Namespace is required.", KubernetesNamespaces.validate(null));
        assertTrue(KubernetesNamespaces.validate("Bad_Name").contains("DNS-1123"));
        assertNull(KubernetesNamespaces.validate("apps"));
    }

    @Test
    public void resolve_rejectsInvalidAfterExpand() {
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "Bad_Name");
        assertThrows(
                IllegalArgumentException.class, () -> KubernetesNamespaces.resolve("${NAMESPACE}", env));
    }
}
