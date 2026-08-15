package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PortainerEndpointKindsTest {

    @Test
    public void isKubernetes_types() {
        assertTrue(PortainerEndpointKinds.isKubernetes(5));
        assertTrue(PortainerEndpointKinds.isKubernetes(6));
        assertTrue(PortainerEndpointKinds.isKubernetes(7));
        assertFalse(PortainerEndpointKinds.isKubernetes(1));
        assertFalse(PortainerEndpointKinds.isKubernetes(2));
        assertFalse(PortainerEndpointKinds.isKubernetes(4));
    }

    @Test
    public void rejectMessage_mentionsExpectedTypes() {
        String msg = PortainerEndpointKinds.rejectNonKubernetesMessage(3, 1, "docker-local");
        assertTrue(msg.contains("Endpoint ID 3"));
        assertTrue(msg.contains("docker-local"));
        assertTrue(msg.contains("Type=1"));
        assertTrue(msg.contains("expected 5=local K8s"));
        assertTrue(msg.contains("Portainer Stack Deployment"));
    }

    @Test
    public void chartRepo_rejectsUserinfoAndLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> ChartRepositoryUrl.normalize("https://user:pass@charts.example/helm"));
        assertThrows(IllegalArgumentException.class,
                () -> ChartRepositoryUrl.normalize("https://127.0.0.1/charts"));
        assertTrue(ChartRepositoryUrl.normalize("https://charts.example/helm")
                .startsWith("https://charts.example"));
    }
}
