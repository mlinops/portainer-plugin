package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class PortainerEnvMergeTest {

    @Test
    public void merge_vaultWinsOnCollision() {
        List<PortainerClient.EnvPair> step = List.of(
                new PortainerClient.EnvPair("IMAGE_TAG", "from-step"),
                new PortainerClient.EnvPair("ONLY_STEP", "a"));
        Map<String, String> vault = new LinkedHashMap<>();
        vault.put("IMAGE_TAG", "from-vault");
        vault.put("ONLY_VAULT", "b");

        List<PortainerClient.EnvPair> merged = PortainerEnvMerge.merge(step, vault);
        assertEquals(3, merged.size());
        assertEquals("from-vault", valueOf(merged, "IMAGE_TAG"));
        assertEquals("a", valueOf(merged, "ONLY_STEP"));
        assertEquals("b", valueOf(merged, "ONLY_VAULT"));
    }

    @Test
    public void merge_emptyVault_keepsStep() {
        List<PortainerClient.EnvPair> step = List.of(new PortainerClient.EnvPair("A", "1"));
        List<PortainerClient.EnvPair> merged = PortainerEnvMerge.merge(step, Map.of());
        assertEquals(1, merged.size());
        assertEquals("1", merged.get(0).value);
    }

    @Test
    public void merge_nullVault_keepsStep() {
        List<PortainerClient.EnvPair> step = List.of(new PortainerClient.EnvPair("A", "1"));
        List<PortainerClient.EnvPair> merged = PortainerEnvMerge.merge(step, null);
        assertEquals(1, merged.size());
        assertEquals("1", merged.get(0).value);
    }

    @Test
    public void merge_emptyStep_vaultOnly() {
        Map<String, String> vault = Map.of("K", "v");
        List<PortainerClient.EnvPair> merged = PortainerEnvMerge.merge(List.of(), vault);
        assertEquals(1, merged.size());
        assertEquals("K", merged.get(0).name);
        assertEquals("v", merged.get(0).value);
    }

    @Test
    public void overlay_existingThenStep_stepWins() {
        List<PortainerClient.EnvPair> existing = List.of(
                new PortainerClient.EnvPair("KEEP", "old"),
                new PortainerClient.EnvPair("UPDATE", "old"));
        List<PortainerClient.EnvPair> step = List.of(
                new PortainerClient.EnvPair("UPDATE", "new"),
                new PortainerClient.EnvPair("ADDED", "x"));

        List<PortainerClient.EnvPair> merged = PortainerEnvMerge.overlay(existing, step);
        assertEquals(3, merged.size());
        assertEquals("old", valueOf(merged, "KEEP"));
        assertEquals("new", valueOf(merged, "UPDATE"));
        assertEquals("x", valueOf(merged, "ADDED"));
    }

    @Test
    public void envKeyNames_listNeverIncludesValues() {
        List<String> names = List.of("RABBITMQ_ERLANG_COOKIE", "IMAGE_TAG");
        String listed = PortainerBuildLogger.formatNameList(names);
        assertEquals("RABBITMQ_ERLANG_COOKIE, IMAGE_TAG", listed);
        assertFalse(listed.contains("never-log-this"));
        assertEquals("(none)", PortainerBuildLogger.formatNameList(List.of()));
    }

    @Test
    public void overlay_nullExisting_keepsStep() {
        List<PortainerClient.EnvPair> step = List.of(new PortainerClient.EnvPair("A", "1"));
        List<PortainerClient.EnvPair> merged = PortainerEnvMerge.overlay(null, step);
        assertEquals(1, merged.size());
        assertEquals("1", merged.get(0).value);
    }

    private static String valueOf(List<PortainerClient.EnvPair> pairs, String name) {
        for (PortainerClient.EnvPair p : pairs) {
            if (name.equals(p.name)) {
                return p.value;
            }
        }
        throw new AssertionError("missing key " + name);
    }
}
