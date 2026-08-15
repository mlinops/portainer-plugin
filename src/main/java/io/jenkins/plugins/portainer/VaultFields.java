package io.jenkins.plugins.portainer;

import hudson.EnvVars;

/**
 * Expanded/normalized Vault step fields for dump + KV read (parse once per perform).
 * Never holds tokens or secret values.
 */
final class VaultFields {

    final String pathRaw;
    final String path;
    final String mount;
    final Integer version;
    final String namespace;
    final String urlRaw;

    private VaultFields(
            String pathRaw,
            String path,
            String mount,
            Integer version,
            String namespace,
            String urlRaw) {
        this.pathRaw = pathRaw;
        this.path = path;
        this.mount = mount;
        this.version = version;
        this.namespace = namespace;
        this.urlRaw = urlRaw;
    }

    /**
     * Expands env macros and normalizes mount/path/version.
     * {@code pathRaw} may be null when the path field is blank; {@link #path} is null then.
     */
    static VaultFields parse(
            String vaultPath,
            String vaultMount,
            String vaultVersion,
            String vaultNamespace,
            String vaultUrl,
            EnvVars buildEnv) {
        String pathRaw = expandOptional(vaultPath, buildEnv);
        String urlRaw = expandOptional(vaultUrl, buildEnv);
        String namespace = expandOptional(vaultNamespace, buildEnv);
        String mount = VaultClient.normalizeMount(expandOptional(vaultMount, buildEnv));
        Integer version = VaultClient.parseVersion(expandOptional(vaultVersion, buildEnv));
        String path = pathRaw == null ? null : VaultClient.normalizeSecretPath(pathRaw);
        return new VaultFields(pathRaw, path, mount, version, namespace, urlRaw);
    }

    /** Trim/expand; blank → {@code null}. Shared by VaultConnections / Stack vault mode label. */
    static String expandOptional(String value, EnvVars buildEnv) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String expanded = buildEnv == null ? value.trim() : buildEnv.expand(value.trim());
        return expanded == null || expanded.isBlank() ? null : expanded.trim();
    }
}
