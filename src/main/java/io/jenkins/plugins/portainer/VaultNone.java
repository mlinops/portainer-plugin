package io.jenkins.plugins.portainer;

import hudson.Extension;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Vault overlay disabled. Stack default. Not listed on Secret.
 */
public final class VaultNone extends VaultConnection {

    static final String SUMMARY = "Vault disabled.";

    @DataBoundConstructor
    public VaultNone() {
    }

    @Override
    public String getMode() {
        return ConnectionMode.NONE;
    }

    @Extension(ordinal = 30)
    @Symbol("vaultNone")
    public static final class DescriptorImpl extends hudson.model.Descriptor<VaultConnection> {

        @Override
        public String getDisplayName() {
            return "Not connected";
        }

        public String getSummary() {
            return SUMMARY;
        }
    }
}
