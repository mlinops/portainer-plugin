package com.datapipe.jenkins.vault.configuration;

import com.datapipe.jenkins.vault.credentials.VaultCredential;
import io.github.jopenlibs.vault.VaultConfig;

/**
 * Test double for HashiCorp Vault Plugin {@code VaultConfiguration}.
 */
public class VaultConfiguration {

    private String vaultUrl;
    private String vaultNamespace;
    private Integer engineVersion = 2;
    private String vaultCredentialId;
    private VaultCredential vaultCredential;
    private VaultConfig vaultConfig = new VaultConfig();
    private int maxRetries = 0;
    private int retryIntervalMilliseconds = 1000;
    private String policies;
    private String prefixPath;

    public VaultConfiguration copy() {
        VaultConfiguration c = new VaultConfiguration();
        c.vaultUrl = vaultUrl;
        c.vaultNamespace = vaultNamespace;
        c.engineVersion = engineVersion;
        c.vaultCredentialId = vaultCredentialId;
        c.vaultCredential = vaultCredential;
        c.vaultConfig = vaultConfig;
        c.maxRetries = maxRetries;
        c.retryIntervalMilliseconds = retryIntervalMilliseconds;
        c.policies = policies;
        c.prefixPath = prefixPath;
        return c;
    }

    public String getVaultUrl() {
        return vaultUrl;
    }

    public void setVaultUrl(String vaultUrl) {
        this.vaultUrl = vaultUrl;
    }

    public String getVaultNamespace() {
        return vaultNamespace;
    }

    public void setVaultNamespace(String vaultNamespace) {
        this.vaultNamespace = vaultNamespace;
    }

    public Integer getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(Integer engineVersion) {
        this.engineVersion = engineVersion;
    }

    public String getVaultCredentialId() {
        return vaultCredentialId;
    }

    public void setVaultCredentialId(String vaultCredentialId) {
        this.vaultCredentialId = vaultCredentialId;
    }

    public VaultCredential getVaultCredential() {
        return vaultCredential;
    }

    public void setVaultCredential(VaultCredential vaultCredential) {
        this.vaultCredential = vaultCredential;
    }

    public VaultConfig getVaultConfig() {
        return vaultConfig;
    }

    public void setVaultConfig(VaultConfig vaultConfig) {
        this.vaultConfig = vaultConfig;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryIntervalMilliseconds() {
        return retryIntervalMilliseconds;
    }

    public void setRetryIntervalMilliseconds(int retryIntervalMilliseconds) {
        this.retryIntervalMilliseconds = retryIntervalMilliseconds;
    }

    public String getPolicies() {
        return policies;
    }

    public void setPolicies(String policies) {
        this.policies = policies;
    }

    public String getPrefixPath() {
        return prefixPath;
    }

    public void setPrefixPath(String prefixPath) {
        this.prefixPath = prefixPath;
    }
}
