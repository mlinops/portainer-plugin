package io.jenkins.plugins.portainer;

import hudson.EnvVars;

import java.io.IOException;
import java.util.regex.Pattern;

/** Kubernetes namespace helpers for Helm (Manifest no longer takes a namespace field). */
final class KubernetesNamespaces {

    static final String DEFAULT = "default";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

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

    static String resolve(String configured, EnvVars buildEnv) {
        String raw = configured == null || configured.isBlank() ? DEFAULT : configured.trim();
        String expanded = buildEnv == null ? raw : buildEnv.expand(raw).trim();
        if (expanded.isBlank()) {
            expanded = DEFAULT;
        }
        String err = validate(expanded);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        return expanded;
    }

    static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Namespace is required.";
        }
        String ns = raw.trim();
        if (ns.length() > 63 || !NAMESPACE_PATTERN.matcher(ns).matches()) {
            return "Namespace must be a DNS-1123 label (lowercase alphanumeric or '-', max 63).";
        }
        return null;
    }
}
