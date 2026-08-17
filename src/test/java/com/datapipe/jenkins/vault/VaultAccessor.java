package com.datapipe.jenkins.vault;

import com.datapipe.jenkins.vault.configuration.GlobalVaultConfiguration;
import com.datapipe.jenkins.vault.configuration.VaultConfiguration;
import com.datapipe.jenkins.vault.credentials.VaultCredential;
import hudson.EnvVars;
import hudson.model.Run;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.response.LogicalResponse;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for HashiCorp Vault Plugin {@code VaultAccessor}. Controllable via static hooks.
 */
public class VaultAccessor {

    public static boolean forcePullThrows;
    public static RuntimeException pullException;
    public static boolean forceRetrieveThrows;
    public static RuntimeException retrieveException;
    public static boolean responseHasErrorsResult;
    public static boolean forceReadThrows;
    public static RuntimeException readException;
    public static Map<String, String> readData = new LinkedHashMap<>();
    public static String lastReadPath;
    public static Integer lastReadEngineVersion;
    public static boolean initCalled;
    public static List<String> lastPolicies;

    private VaultConfig config;
    private VaultCredential credential;
    private List<String> policies = List.of();
    private int maxRetries;
    private int retryIntervalMilliseconds;

    public static void resetForTests() {
        forcePullThrows = false;
        pullException = null;
        forceRetrieveThrows = false;
        retrieveException = null;
        responseHasErrorsResult = false;
        forceReadThrows = false;
        readException = null;
        readData = new LinkedHashMap<>();
        readData.put("IMAGE_TAG", "1.2.3");
        lastReadPath = null;
        lastReadEngineVersion = null;
        initCalled = false;
        lastPolicies = null;
    }

    public static VaultConfiguration pullAndMergeConfiguration(Run<?, ?> run, VaultConfiguration stepOverrides) {
        if (forcePullThrows) {
            throw pullException != null
                    ? pullException
                    : new RuntimeException("No configuration found for folder");
        }
        VaultConfiguration base = GlobalVaultConfiguration.get().getConfiguration();
        if (base == null) {
            throw new RuntimeException("No configuration found for folder");
        }
        VaultConfiguration merged = base.copy();
        if (stepOverrides != null) {
            if (stepOverrides.getVaultNamespace() != null && !stepOverrides.getVaultNamespace().isBlank()) {
                merged.setVaultNamespace(stepOverrides.getVaultNamespace());
            }
            if (stepOverrides.getEngineVersion() != null) {
                merged.setEngineVersion(stepOverrides.getEngineVersion());
            }
        }
        return merged;
    }

    public static VaultCredential retrieveVaultCredentials(Run<?, ?> run, VaultConfiguration configuration) {
        if (forceRetrieveThrows) {
            throw retrieveException != null
                    ? retrieveException
                    : new RuntimeException("CredentialsUnavailableException");
        }
        String id = configuration == null ? null : configuration.getVaultCredentialId();
        return new VaultCredential(id == null || id.isBlank() ? "retrieved" : id);
    }

    @SuppressWarnings("unused")
    private static List<String> generatePolicies(String policiesRaw, EnvVars buildEnv) {
        List<String> out = new ArrayList<>();
        if (policiesRaw != null && !policiesRaw.isBlank()) {
            String expanded = buildEnv == null ? policiesRaw : buildEnv.expand(policiesRaw);
            out.add(expanded.trim());
        }
        return out;
    }

    public static boolean responseHasErrors(
            VaultConfiguration configuration,
            PrintStream logger,
            String path,
            LogicalResponse response) {
        if (logger != null && responseHasErrorsResult) {
            logger.println("Vault error at " + path);
        }
        return responseHasErrorsResult;
    }

    public void setConfig(VaultConfig config) {
        this.config = config;
    }

    public void setCredential(VaultCredential credential) {
        this.credential = credential;
    }

    public void setPolicies(List<String> policies) {
        this.policies = policies == null ? List.of() : List.copyOf(policies);
        lastPolicies = this.policies;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setRetryIntervalMilliseconds(int retryIntervalMilliseconds) {
        this.retryIntervalMilliseconds = retryIntervalMilliseconds;
    }

    public void init() {
        initCalled = true;
        if (config == null || credential == null) {
            throw new IllegalStateException("VaultAccessor requires config and credential");
        }
    }

    public LogicalResponse read(String path, Integer engineVersion) {
        lastReadPath = path;
        lastReadEngineVersion = engineVersion;
        if (forceReadThrows) {
            throw readException != null ? readException : new RuntimeException("read failed");
        }
        LogicalResponse response = new LogicalResponse();
        response.setData(readData);
        return response;
    }

    public VaultConfig getConfig() {
        return config;
    }

    public VaultCredential getCredential() {
        return credential;
    }

    public List<String> getPolicies() {
        return policies;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getRetryIntervalMilliseconds() {
        return retryIntervalMilliseconds;
    }
}
