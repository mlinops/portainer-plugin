package io.jenkins.plugins.portainer;

/**
 * Portainer environment {@code Type} values relevant to Kubernetes vs Docker.
 *
 * @see <a href="https://github.com/portainer/portainer/blob/develop/api/portainer.go">portainer.EndpointType</a>
 */
final class PortainerEndpointKinds {

    /** Local Kubernetes. */
    static final int KUBERNETES_LOCAL = 5;
    /** Agent on Kubernetes. */
    static final int AGENT_ON_KUBERNETES = 6;
    /** Edge Agent on Kubernetes. */
    static final int EDGE_AGENT_ON_KUBERNETES = 7;

    private PortainerEndpointKinds() {
    }

    static boolean isKubernetes(int type) {
        return type == KUBERNETES_LOCAL
                || type == AGENT_ON_KUBERNETES
                || type == EDGE_AGENT_ON_KUBERNETES;
    }

    static String rejectNonKubernetesMessage(int endpointId, int type, String endpointName) {
        String namePart = endpointName == null || endpointName.isBlank()
                ? ""
                : " (" + endpointName.trim() + ")";
        String typePart = type == Integer.MIN_VALUE ? "unknown" : String.valueOf(type);
        return "Endpoint ID "
                + endpointId
                + namePart
                + " is not a Kubernetes Portainer environment (Type="
                + typePart
                + "; expected 5=local K8s, 6=agent K8s, or 7=edge agent K8s). "
                + "Use Portainer Stack Deployment for Docker Compose/Swarm endpoints.";
    }
}
