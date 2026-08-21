package io.jenkins.plugins.portainer;

import java.util.Locale;

/**
 * Portainer / Vault connection mode on the build step.
 * <p>
 * Portainer: {@link #INHERIT} (default) or {@link #MANUAL}.
 * Vault nested types use these strings via {@link VaultConnection#getMode()}.
 * Freestyle Portainer still binds {@code f:radioBlock inline="true"} as a plain string.
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
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (INHERIT.equals(v) || MANUAL.equals(v) || NONE.equals(v)) {
            return v;
        }
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
}
