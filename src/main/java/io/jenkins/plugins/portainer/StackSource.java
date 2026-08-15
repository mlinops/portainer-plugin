package io.jenkins.plugins.portainer;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Stack deploy source for {@code portainerStack}: Git repository (default) or inline YAML.
 * <p>
 * Freestyle {@code f:radioBlock} posts nested {@code {"value":"repository"|"yaml", …}}.
 * Flatten in {@code Descriptor.newInstance} before Stapler bind (same pattern as
 * {@link ConnectionMode}).
 */
final class StackSource {

    static final String REPOSITORY = "repository";
    static final String YAML = "yaml";

    private StackSource() {
    }

    static String normalize(String raw) {
        return normalize(raw, REPOSITORY);
    }

    static String normalize(String raw, String defaultMode) {
        if (raw == null || raw.isBlank()) {
            return defaultMode;
        }
        String v = raw.trim();
        if (v.startsWith("{") && v.contains("value")) {
            try {
                JSONObject o = JSONObject.fromObject(v);
                Object mode = o.opt("value");
                if (mode != null && !JSONNull.getInstance().equals(mode)) {
                    v = String.valueOf(mode).trim();
                }
            } catch (RuntimeException ignored) {
                // not JSON — fall through
            }
        }
        v = v.toLowerCase(Locale.ROOT);
        if (REPOSITORY.equals(v) || YAML.equals(v)) {
            return v;
        }
        // Aliases for Pipeline convenience
        if ("git".equals(v) || "repo".equals(v)) {
            return REPOSITORY;
        }
        if ("manual".equals(v) || "string".equals(v) || "file".equals(v)) {
            return YAML;
        }
        return defaultMode;
    }

    static boolean isYaml(String mode) {
        return YAML.equals(normalize(mode));
    }

    static boolean isRepository(String mode) {
        return !isYaml(mode);
    }

    /**
     * Flatten Freestyle {@code stackSource} radioBlock into a string mode + top-level nested fields.
     */
    static void flattenRadioBlock(JSONObject formData) {
        ConnectionMode.flattenRadioBlock(
                formData,
                "stackSource",
                REPOSITORY,
                StackSource::normalize,
                "repositoryUrl",
                "composeFilePath",
                "gitCredentialsId",
                "repositoryReferenceName",
                "stackFileContent");
    }
}
