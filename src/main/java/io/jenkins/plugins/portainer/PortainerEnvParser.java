package io.jenkins.plugins.portainer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses multiline env text into Portainer {@code Env[]} pairs.
 * Blank lines and {@code #} comments are skipped.
 * Each non-empty line is either {@code KEY=VALUE} or a bare {@code KEY}
 * (shorthand for {@code KEY=${KEY}}, resolved from the build environment after parse).
 */
final class PortainerEnvParser {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PortainerEnvParser() {
    }

    static List<PortainerClient.EnvPair> parse(String envText) {
        List<PortainerClient.EnvPair> out = new ArrayList<>();
        if (envText == null || envText.isBlank()) {
            return out;
        }
        for (String raw : envText.split("\\R")) {
            if (shouldSkip(raw)) {
                continue;
            }
            out.add(parseLine(raw.trim()));
        }
        return out;
    }

    private static boolean shouldSkip(String raw) {
        if (raw == null) {
            return true;
        }
        String line = raw.trim();
        return line.isEmpty() || line.startsWith("#");
    }

    private static PortainerClient.EnvPair parseLine(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) {
            requireValidKey(line);
            return new PortainerClient.EnvPair(line, "${" + line + "}");
        }
        if (eq == 0) {
            throw new IllegalArgumentException(
                    "Invalid env line (expected KEY=VALUE or KEY): " + truncate(line, 80) + ".");
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Invalid env line: empty key.");
        }
        requireValidKey(key);
        return new PortainerClient.EnvPair(key, value);
    }

    private static void requireValidKey(String key) {
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Invalid env key (use letters, digits, and '_'; starting with a letter or '_'): "
                            + truncate(key, 80) + ".");
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
