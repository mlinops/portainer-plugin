package io.jenkins.plugins.portainer;

import hudson.Extension;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * HashiCorp Vault Plugin System configuration (optional plugin).
 */
public final class VaultInherit extends VaultConnection.Kv {

    @DataBoundConstructor
    public VaultInherit() {
        // Stapler / Pipeline vaultInherit(); KV fields are DataBoundSetter on Kv.
    }

    @Override
    public String getMode() {
        return ConnectionMode.INHERIT;
    }

    @Extension(ordinal = 20)
    @Symbol("vaultInherit")
    public static final class DescriptorImpl extends VaultConnection.KvDescriptor {

        @Override
        public String getDisplayName() {
            return "Inherit from System";
        }

        public String getVaultInheritSummary() {
            return VaultPluginInherit.inheritSummary();
        }
    }
}
