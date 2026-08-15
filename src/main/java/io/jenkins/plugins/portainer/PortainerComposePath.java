package io.jenkins.plugins.portainer;

import java.util.Locale;

/**
 * Validates Compose file paths used when creating stacks from a Git repository.
 */
final class PortainerComposePath {

    private PortainerComposePath() {
    }

    /**
     * @return normalized relative path
     * @throws IllegalArgumentException if empty or invalid
     */
    static String normalize(String raw) {
        return normalize(raw, "Compose file path");
    }

    /**
     * @param label human label used in error messages (e.g. {@code Values file path})
     * @return normalized relative path
     * @throws IllegalArgumentException if empty or invalid
     */
    static String normalize(String raw, String label) {
        String err = validate(raw, label);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        return raw.trim().replace('\\', '/');
    }

    /**
     * @return error message, or {@code null} if valid
     */
    static String validate(String raw) {
        return validate(raw, "Compose file path");
    }

    /**
     * @param label human label used in error messages (e.g. {@code Values file path})
     * @return error message, or {@code null} if valid
     */
    static String validate(String raw, String label) {
        String name = label == null || label.isBlank() ? "File path" : label.trim();
        if (raw == null || raw.isBlank()) {
            return name + " is required.";
        }
        String path = raw.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return name + " is required.";
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            return name + " must be relative to the repository root (not an absolute path).";
        }
        if (path.contains("\0")) {
            return name + " is invalid.";
        }
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return name + " must not contain empty segments or '..'.";
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".yml") || lower.endsWith(".yaml"))) {
            return name + " should end with .yml or .yaml.";
        }
        return null;
    }
}
