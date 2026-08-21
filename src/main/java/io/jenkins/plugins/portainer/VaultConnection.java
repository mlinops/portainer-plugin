package io.jenkins.plugins.portainer;

import hudson.model.Descriptor;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundSetter;

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
     * Former persisted Stack/Secret fields ({@code vaultConnectionMode} + siblings).
     * Stack default is none; Secret maps explicit none to inherit.
     */
    static VaultConnection fromLegacy(
            String mode,
            String vaultUrl,
            String vaultAppRoleCredentialsId,
            String vaultPath,
            String vaultMount,
            String vaultNamespace,
            String vaultVersion,
            boolean secretStep) {
        String normalized = blankToNull(mode);
        if (normalized != null) {
            String resolved = ConnectionMode.normalize(
                    normalized, secretStep ? ConnectionMode.INHERIT : ConnectionMode.NONE);
            if (secretStep && ConnectionMode.isNone(resolved)) {
                resolved = ConnectionMode.INHERIT;
            }
            return fromMode(
                    resolved,
                    vaultUrl,
                    vaultAppRoleCredentialsId,
                    vaultPath,
                    vaultMount,
                    vaultNamespace,
                    vaultVersion);
        }
        if (nonBlank(vaultUrl) || nonBlank(vaultAppRoleCredentialsId)) {
            return fromMode(
                    ConnectionMode.MANUAL,
                    vaultUrl,
                    vaultAppRoleCredentialsId,
                    vaultPath,
                    vaultMount,
                    vaultNamespace,
                    vaultVersion);
        }
        if (nonBlank(vaultPath)) {
            return fromMode(
                    ConnectionMode.INHERIT,
                    vaultUrl,
                    vaultAppRoleCredentialsId,
                    vaultPath,
                    vaultMount,
                    vaultNamespace,
                    vaultVersion);
        }
        return secretStep ? new VaultInherit() : new VaultNone();
    }

    private static VaultConnection fromMode(
            String mode,
            String vaultUrl,
            String vaultAppRoleCredentialsId,
            String vaultPath,
            String vaultMount,
            String vaultNamespace,
            String vaultVersion) {
        if (ConnectionMode.isNone(mode)) {
            return new VaultNone();
        }
        if (ConnectionMode.isManual(mode)) {
            VaultManual manual = new VaultManual(vaultUrl, vaultAppRoleCredentialsId);
            applyKv(manual, vaultPath, vaultMount, vaultNamespace, vaultVersion);
            return manual;
        }
        VaultInherit inherit = new VaultInherit();
        applyKv(inherit, vaultPath, vaultMount, vaultNamespace, vaultVersion);
        return inherit;
    }

    private static void applyKv(
            Kv kv, String path, String mount, String namespace, String version) {
        kv.setVaultPath(path);
        kv.setVaultMount(mount);
        kv.setVaultNamespace(namespace);
        kv.setVaultVersion(version);
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
}
