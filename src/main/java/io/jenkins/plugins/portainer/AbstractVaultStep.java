package io.jenkins.plugins.portainer;

import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;

/**
 * Shared leftover {@code vaultConnectionMode} siblings for Stack and Secret.
 * XStream writes these as direct children of the builder element.
 */
abstract class AbstractVaultStep extends Builder implements SimpleBuildStep {

    /** Nested Vault connection. Null is resolved from leftover fields in {@link #readResolveVault}. */
    protected VaultConnection vault;
    /** Former persisted field; migrated in {@link #readResolveVault}. */
    protected String vaultConnectionMode;
    protected String vaultUrl;
    protected String vaultAppRoleCredentialsId;
    protected String vaultPath;
    protected String vaultMount;
    protected String vaultNamespace;
    protected String vaultVersion;

    final Object readResolveVault(boolean secretStep) {
        vault = VaultConnection.migrate(
                vault,
                new VaultConnection.Leftover(
                        vaultConnectionMode,
                        vaultUrl,
                        vaultAppRoleCredentialsId,
                        vaultPath,
                        vaultMount,
                        vaultNamespace,
                        vaultVersion),
                secretStep);
        vaultConnectionMode = vaultUrl = vaultAppRoleCredentialsId = vaultPath = vaultMount =
                vaultNamespace = vaultVersion = null;
        return this;
    }
}
