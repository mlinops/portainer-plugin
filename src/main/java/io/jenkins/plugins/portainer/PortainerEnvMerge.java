package io.jenkins.plugins.portainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges Portainer stack {@code Env[]} layers. Later overlays win on key collisions.
 * Typical order: existing stack → step {@code env} → Vault.
 */
final class PortainerEnvMerge {

    private PortainerEnvMerge() {
    }

    /**
     * Overlay {@code top} onto {@code base}. Keys only in {@code top} are appended in top order.
     */
    static List<PortainerClient.EnvPair> overlay(
            List<PortainerClient.EnvPair> base, List<PortainerClient.EnvPair> top) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        putAll(map, base);
        putAll(map, top);
        return toList(map);
    }

    /**
     * @param stepEnv       pairs from step text {@code env} (may be empty)
     * @param vaultOverlay  flat map from Vault KV v2 (empty or null = no overlay)
     * @return merged list; Vault wins on collisions
     */
    static List<PortainerClient.EnvPair> merge(
            List<PortainerClient.EnvPair> stepEnv, Map<String, String> vaultOverlay) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        putAll(map, stepEnv);
        if (vaultOverlay != null && !vaultOverlay.isEmpty()) {
            for (Map.Entry<String, String> e : vaultOverlay.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                map.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return toList(map);
    }

    private static void putAll(LinkedHashMap<String, String> map, List<PortainerClient.EnvPair> pairs) {
        if (pairs == null) {
            return;
        }
        for (PortainerClient.EnvPair p : pairs) {
            if (p == null || p.name == null || p.name.isBlank()) {
                continue;
            }
            map.put(p.name, p.value == null ? "" : p.value);
        }
    }

    private static List<PortainerClient.EnvPair> toList(LinkedHashMap<String, String> map) {
        List<PortainerClient.EnvPair> out = new ArrayList<>(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            out.add(new PortainerClient.EnvPair(e.getKey(), e.getValue()));
        }
        return out;
    }
}
