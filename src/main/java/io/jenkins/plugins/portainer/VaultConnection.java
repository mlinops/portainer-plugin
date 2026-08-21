package io.jenkins.plugins.portainer;

import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.util.ArrayList;
import java.util.List;

/**
 * Nested Vault connection on Stack / Secret. Freestyle: {@code f:dropdownDescriptorSelector}.
 * Pipeline symbols: {@code vaultNone}, {@code vaultInherit}, {@code vaultManual}.
 */
public abstract class VaultConnection extends hudson.model.AbstractDescribableImpl<VaultConnection> {

    public abstract String getMode();

    public String getVaultUrl() {
        return null;
    }

    public String getVaultAppRoleCredentialsId() {
        return null;
    }

    public String getVaultPath() {
        return null;
    }

    public String getVaultMount() {
        return null;
    }

    public String getVaultNamespace() {
        return null;
    }

    public String getVaultVersion() {
        return null;
    }

    public final boolean isNone() {
        return ConnectionMode.isNone(getMode());
    }

    final VaultFields toFields(hudson.EnvVars buildEnv) {
        return VaultFields.parse(
                getVaultPath(),
                getVaultMount(),
                getVaultVersion(),
                getVaultNamespace(),
                getVaultUrl(),
                buildEnv);
    }

    static List<Descriptor<VaultConnection>> descriptors(boolean includeNone) {
        List<Descriptor<VaultConnection>> out = new ArrayList<>();
        for (Descriptor<VaultConnection> d : Jenkins.get().getDescriptorList(VaultConnection.class)) {
            if (!includeNone && d instanceof VaultNone.DescriptorImpl) {
                continue;
            }
            out.add(d);
        }
        return out;
    }

    /**
     * Former persisted {@code vaultConnectionMode} siblings on Stack/Secret (XStream leftover).
     */
    record Leftover(
            String mode,
            String url,
            String credentialsId,
            String path,
            String mount,
            String namespace,
            String version) {}

    /**
     * XStream leftover fields on Stack/Secret. When {@code vault} is already nested, it is kept
     * (Secret still replaces {@link VaultNone}). Leftover strings are not cleared here.
     */
    static VaultConnection migrate(VaultConnection vault, Leftover leftover, boolean secretStep) {
        VaultConnection resolved = vault;
        if (resolved == null) {
            resolved = fromLegacy(leftover, secretStep);
        }
        if (secretStep && resolved instanceof VaultNone) {
            return new VaultInherit();
        }
        return resolved;
    }

    /**
     * Former persisted Stack/Secret fields ({@code vaultConnectionMode} + siblings).
     * Stack default is none; Secret maps explicit none to inherit.
     */
    static VaultConnection fromLegacy(Leftover leftover, boolean secretStep) {
        String normalized = blankToNull(leftover.mode());
        if (normalized != null) {
            String resolved = ConnectionMode.normalize(
                    normalized, secretStep ? ConnectionMode.INHERIT : ConnectionMode.NONE);
            if (secretStep && ConnectionMode.isNone(resolved)) {
                resolved = ConnectionMode.INHERIT;
            }
            return fromMode(resolved, leftover);
        }
        if (nonBlank(leftover.url()) || nonBlank(leftover.credentialsId())) {
            return fromMode(ConnectionMode.MANUAL, leftover);
        }
        if (nonBlank(leftover.path())) {
            return fromMode(ConnectionMode.INHERIT, leftover);
        }
        return secretStep ? new VaultInherit() : new VaultNone();
    }

    private static VaultConnection fromMode(String mode, Leftover leftover) {
        if (ConnectionMode.isNone(mode)) {
            return new VaultNone();
        }
        if (ConnectionMode.isManual(mode)) {
            VaultManual manual = new VaultManual(leftover.url(), leftover.credentialsId());
            applyKv(manual, leftover);
            return manual;
        }
        VaultInherit inherit = new VaultInherit();
        applyKv(inherit, leftover);
        return inherit;
    }

    private static void applyKv(Kv kv, Leftover leftover) {
        kv.setVaultPath(leftover.path());
        kv.setVaultMount(leftover.mount());
        kv.setVaultNamespace(leftover.namespace());
        kv.setVaultVersion(leftover.version());
    }

    static FormValidation checkUrl(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            VaultUrl.normalizeBaseUrlSyntaxOnly(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static FormValidation checkPath(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            VaultClient.normalizeSecretPath(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static FormValidation checkMount(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            VaultClient.normalizeMount(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static FormValidation checkVersion(String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            VaultClient.parseVersion(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Inherit / Manual KV fields. */
    public abstract static class Kv extends VaultConnection {
        private String vaultPath;
        private String vaultMount;
        private String vaultNamespace;
        private String vaultVersion;

        @Override
        public String getVaultPath() {
            return vaultPath;
        }

        @DataBoundSetter
        public void setVaultPath(String vaultPath) {
            this.vaultPath = blankToNull(vaultPath);
        }

        @Override
        public String getVaultMount() {
            return vaultMount;
        }

        @DataBoundSetter
        public void setVaultMount(String vaultMount) {
            this.vaultMount = blankToNull(vaultMount);
        }

        @Override
        public String getVaultNamespace() {
            return vaultNamespace;
        }

        @DataBoundSetter
        public void setVaultNamespace(String vaultNamespace) {
            this.vaultNamespace = blankToNull(vaultNamespace);
        }

        @Override
        public String getVaultVersion() {
            return vaultVersion;
        }

        @DataBoundSetter
        public void setVaultVersion(String vaultVersion) {
            this.vaultVersion = blankToNull(vaultVersion);
        }
    }

    /**
     * Form checks for Inherit / Manual KV fields. Stapler binds {@code doCheck*} from this type
     * on subclass descriptors.
     */
    public abstract static class KvDescriptor extends Descriptor<VaultConnection> {

        @POST
        public FormValidation doCheckVaultPath(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return checkPath(value);
        }

        @POST
        public FormValidation doCheckVaultMount(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return checkMount(value);
        }

        @POST
        public FormValidation doCheckVaultVersion(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return checkVersion(value);
        }
    }
}
