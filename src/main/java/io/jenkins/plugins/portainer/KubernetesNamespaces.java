package io.jenkins.plugins.portainer;

import java.io.IOException;

/** Shared Kubernetes namespace ensure logging for Manifest and Helm steps. */
final class KubernetesNamespaces {

    private KubernetesNamespaces() {
    }

    static void ensure(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            String namespace,
            PortainerBuildLogger log) throws IOException {
        log.info("Ensuring namespace=" + namespace);
        String result = client.ensureNamespace(connection.baseUrl, apiKey, endpoint, namespace);
        log.info("Namespace ready name=" + namespace + " result=" + result);
    }
}
