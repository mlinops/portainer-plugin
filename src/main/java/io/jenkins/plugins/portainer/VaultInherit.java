package io.jenkins.plugins.portainer;

import hudson.Extension;
import hudson.model.Item;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * HashiCorp Vault Plugin System configuration (optional plugin).
 */
public final class VaultInherit extends VaultConnection.Kv {

    @DataBoundConstructor
    public VaultInherit() {
    }

    @Override
    public String getMode() {
        return ConnectionMode.INHERIT;
    }

    @Extension(ordinal = 20)
    @Symbol("vaultInherit")
    public static final class DescriptorImpl extends hudson.model.Descriptor<VaultConnection> {

        @Override
        public String getDisplayName() {
            return "Inherit from System";
        }

        public String getVaultInheritSummary() {
            return VaultPluginInherit.inheritSummary();
        }

        @POST
        public FormValidation doCheckVaultPath(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return VaultConnection.checkPath(value);
        }

        @POST
        public FormValidation doCheckVaultMount(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return VaultConnection.checkMount(value);
        }

        @POST
        public FormValidation doCheckVaultVersion(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return VaultConnection.checkVersion(value);
        }
    }
}
