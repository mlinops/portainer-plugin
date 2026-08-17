package com.datapipe.jenkins.vault.credentials;

/**
 * Test double for HashiCorp Vault Plugin credential type loaded by reflection.
 */
public final class VaultCredential {

    private final String id;

    public VaultCredential() {
        this("test-cred");
    }

    public VaultCredential(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
