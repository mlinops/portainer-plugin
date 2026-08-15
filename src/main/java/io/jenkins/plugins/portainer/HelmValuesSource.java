package io.jenkins.plugins.portainer;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Helm values source for {@code portainerHelm}: none (default), Git repository file, or inline YAML.
 * <p>
 * Freestyle {@code f:radioBlock} posts nested {@code {"value":"none"|"repository"|"yaml", …}}.
 * Flatten in {@code Descriptor.newInstance} before Stapler bind (same pattern as
 * {@link StackSource} / {@link ConnectionMode}).
 */
final class HelmValuesSource {

    static final String NONE = "none";
    static final String REPOSITORY = "repository";
    static final String YAML = "yaml";

    private HelmValuesSource() {
    }

    /**
     * Resolve effective mode. When {@code valuesSource} is unset/blank: non-blank {@code values}
     * migrates to {@link #YAML}; otherwise {@link #NONE}.
     */
    static String resolve(String valuesSource, String values) {
        if (valuesSource != null && !valuesSource.isBlank()) {
            return normalize(valuesSource, NONE);
        }
        if (values != null && !values.isBlank()) {
            return YAML;
        }
        return NONE;
    }

    static String normalize(String raw) {
        return normalize(raw, NONE);
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
        if (NONE.equals(v) || REPOSITORY.equals(v) || YAML.equals(v)) {
            return v;
        }
        if ("off".equals(v) || "disabled".equals(v) || "empty".equals(v) || "chart".equals(v)) {
            return NONE;
        }
        if ("git".equals(v) || "repo".equals(v)) {
            return REPOSITORY;
        }
        if ("manual".equals(v) || "string".equals(v) || "file".equals(v) || "inline".equals(v)) {
            return YAML;
        }
        return defaultMode;
    }

    static boolean isNone(String mode) {
        return NONE.equals(normalize(mode));
    }

    static boolean isRepository(String mode) {
        return REPOSITORY.equals(normalize(mode));
    }

    static boolean isYaml(String mode) {
        return YAML.equals(normalize(mode));
    }

    /**
     * Flatten Freestyle {@code valuesSource} radioBlock into a string mode + top-level nested fields.
     */
    static void flattenRadioBlock(JSONObject formData) {
        ConnectionMode.flattenRadioBlock(
                formData,
                "valuesSource",
                NONE,
                HelmValuesSource::normalize,
                "valuesRepositoryUrl",
                "valuesFilePath",
                "valuesGitCredentialsId",
                "valuesRepositoryReferenceName",
                "values");
    }
}
