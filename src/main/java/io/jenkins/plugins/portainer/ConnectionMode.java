package io.jenkins.plugins.portainer;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Portainer / Vault connection mode on the build step.
 * <p>
 * Portainer: {@link #INHERIT} (default) or {@link #MANUAL}.
 * Vault: {@link #NONE} (default), {@link #INHERIT}, or {@link #MANUAL}.
 * <p>
 * Freestyle {@code f:radioBlock} submits a nested object
 * {@code {"value":"inherit"|"manual"|"none", …fields}}, while Pipeline and XStream use a plain
 * string. {@link #flattenRadioBlock} must run in {@code Descriptor.newInstance} before Stapler
 * bind — Stapler cannot convert that JSON into a {@code String} (or {@code Object}) setter
 * parameter.
 */
final class ConnectionMode {

    static final String INHERIT = "inherit";
    static final String MANUAL = "manual";
    /** Vault only: disable overlay (no Vault HTTP / Plugin calls). */
    static final String NONE = "none";

    private ConnectionMode() {
    }

    static String normalize(String raw, String defaultMode) {
        if (raw == null || raw.isBlank()) {
            return defaultMode;
        }
        String v = raw.trim();
        // Form validation / odd clients may pass radioBlock JSON as a string.
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
        if (INHERIT.equals(v) || MANUAL.equals(v) || NONE.equals(v)) {
            return v;
        }
        // Aliases for Vault off
        if ("off".equals(v) || "disabled".equals(v) || "disconnected".equals(v)) {
            return NONE;
        }
        return defaultMode;
    }

    static boolean isManual(String mode) {
        return MANUAL.equals(normalize(mode, INHERIT));
    }

    static boolean isNone(String mode) {
        return NONE.equals(normalize(mode, INHERIT));
    }

    /**
     * True only for {@link #INHERIT}. Portainer form checks that are “not Manual” should use
     * {@code !isManual(mode)} so unknown/none still fall through to Inherit behaviour.
     */
    static boolean isInherit(String mode) {
        return INHERIT.equals(normalize(mode, INHERIT));
    }

    /**
     * Flatten Freestyle {@code f:radioBlock} JSON under {@code modeKey} into a string mode plus
     * top-level nested Manual fields so Stapler can bind {@code String} setters.
     * <p>
     * Example: {@code "portainerConnectionMode":{"value":"manual","portainerUrl":"…"}} becomes
     * {@code "portainerConnectionMode":"manual"} and {@code "portainerUrl":"…"}.
     * Already-string modes (Pipeline) are left unchanged.
     */
    static void flattenRadioBlock(JSONObject formData, String modeKey, String... nestedFields) {
        flattenRadioBlock(formData, modeKey, INHERIT, ConnectionMode::normalize, nestedFields);
    }

    /**
     * Same as {@link #flattenRadioBlock(JSONObject, String, String...)} with a custom mode
     * normalizer (e.g. stack source {@code repository}|{@code yaml}).
     */
    static void flattenRadioBlock(
            JSONObject formData,
            String modeKey,
            String defaultMode,
            java.util.function.BiFunction<String, String, String> normalizer,
            String... nestedFields) {
        if (formData == null || modeKey == null || !formData.has(modeKey)) {
            return;
        }
        Object raw = formData.get(modeKey);
        if (!(raw instanceof JSONObject)) {
            return;
        }
        JSONObject block = (JSONObject) raw;
        java.util.function.BiFunction<String, String, String> norm =
                normalizer == null ? ConnectionMode::normalize : normalizer;
        String fallback = defaultMode == null || defaultMode.isBlank() ? INHERIT : defaultMode;
        formData.put(modeKey, norm.apply(stringFromJson(block, "value"), fallback));
        if (nestedFields == null) {
            return;
        }
        for (String field : nestedFields) {
            if (field == null || !block.has(field)) {
                continue;
            }
            Object value = block.get(field);
            if (value == null || JSONNull.getInstance().equals(value)) {
                continue;
            }
            formData.put(field, value);
        }
    }

    private static String stringFromJson(JSONObject json, String key) {
        if (json == null || json.isNullObject()) {
            return null;
        }
        Object value = json.opt(key);
        if (value == null || JSONNull.getInstance().equals(value)) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s.trim();
    }
}
