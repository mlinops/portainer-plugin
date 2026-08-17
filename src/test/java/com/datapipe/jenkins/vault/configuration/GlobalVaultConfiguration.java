package com.datapipe.jenkins.vault.configuration;

/**
 * Test double for HashiCorp Vault Plugin {@code GlobalVaultConfiguration}.
 */
public final class GlobalVaultConfiguration {

    private static GlobalVaultConfiguration instance = new GlobalVaultConfiguration();

    private VaultConfiguration configuration = new VaultConfiguration();

    /** When true, {@link #get()} returns null (covers Inherit null-global branch). */
    public static boolean forceGetNull;

    public static GlobalVaultConfiguration get() {
        if (forceGetNull) {
            return null;
        }
        return instance;
    }

    /** Reset singleton state between tests. */
    public static void resetForTests() {
        forceGetNull = false;
        instance = new GlobalVaultConfiguration();
    }

    public VaultConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(VaultConfiguration configuration) {
        this.configuration = configuration;
    }
}
