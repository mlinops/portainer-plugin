package io.github.jopenlibs.vault.response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test double for vault-java-driver {@code LogicalResponse} used by Vault Inherit reflection.
 */
public final class LogicalResponse {

    private Map<String, String> data = new LinkedHashMap<>();

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}
