package io.jenkins.plugins.portainer;

import java.io.IOException;

/** Live Kubernetes resources for a Portainer stack (applications API). */
final class ManifestDeployVerifier {

    private ManifestDeployVerifier() {
    }

    static void requireLiveResources(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            int stackId,
            String stackName,
            String whenMissing) throws IOException {
        if (stackId < 0 && (stackName == null || stackName.isBlank())) {
            return;
        }
        if (client.hasLiveStackResources(
                connection.baseUrl, apiKey, endpoint, stackId, stackName)) {
            return;
        }
        throw new IOException(whenMissing);
    }
}
