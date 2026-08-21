package io.jenkins.plugins.portainer;

import hudson.Extension;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Self-contained HTTP AppRole on the step. Vault Plugin is not required.
 */
public final class VaultManual extends VaultConnection.Kv {

    private final String vaultUrl;
    private final String vaultAppRoleCredentialsId;

    @DataBoundConstructor
    public VaultManual(String vaultUrl, String vaultAppRoleCredentialsId) {
        this.vaultUrl = vaultUrl == null ? "" : vaultUrl.trim();
        this.vaultAppRoleCredentialsId = VaultConnection.blankToNull(vaultAppRoleCredentialsId);
    }

    @Override
    public String getMode() {
        return ConnectionMode.MANUAL;
    }

    @Override
    public String getVaultUrl() {
        return vaultUrl;
    }

    @Override
    public String getVaultAppRoleCredentialsId() {
        return vaultAppRoleCredentialsId;
    }

    @Extension(ordinal = 10)
    @Symbol("vaultManual")
    public static final class DescriptorImpl extends hudson.model.Descriptor<VaultConnection> {

        @Override
        public String getDisplayName() {
            return "Manual";
        }

        @POST
        public FormValidation doCheckVaultUrl(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return VaultConnection.checkUrl(value);
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

        @POST
        public ListBoxModel doFillVaultAppRoleCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String vaultAppRoleCredentialsId) {
            return PortainerCredentials.fillVaultAppRoleCredentials(item, vaultAppRoleCredentialsId);
        }
    }
}
