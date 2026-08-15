package io.jenkins.plugins.portainer;

import java.util.regex.Pattern;

/**
 * Portainer Docker stack name rules (UI {@code STACK_NAME_VALIDATION_REGEX}).
 *
 * @see <a href="https://github.com/portainer/portainer/blob/develop/app/react/constants.ts">Portainer constants</a>
 */
final class PortainerStackName {

    /**
     * Official Portainer frontend pattern: lower-case alphanumeric, {@code _} or {@code -}.
     */
    static final Pattern PATTERN = Pattern.compile("^[-_a-z0-9]+$");

    static final String RULES_MESSAGE =
            "Stack name must consist of lowercase alphanumeric characters, '_' or '-' "
                    + "(for example 'my-name' or 'abc-123').";

    private PortainerStackName() {
    }

    static void requireValid(String raw) {
        String err = validate(raw);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    /**
     * Like {@link #requireValid(String)} but blank is allowed (Kubernetes Manifest).
     */
    static void requireValidOptional(String raw) {
        String err = validateOptional(raw);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    /**
     * @return error message, or {@code null} if valid
     */
    static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Stack name is required.";
        }
        return validateFormat(raw.trim());
    }

    /**
     * Blank is OK; when present, same format rules as {@link #validate(String)}.
     *
     * @return error message, or {@code null} if valid or blank
     */
    static String validateOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return validateFormat(raw.trim());
    }

    private static String validateFormat(String name) {
        if (!PATTERN.matcher(name).matches()) {
            return RULES_MESSAGE;
        }
        return null;
    }
}
