package io.jenkins.plugins.portainer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Content-addressed Docker Swarm config names: {@code {basename}-{hash8}}.
 */
final class SwarmConfigNaming {

    private static final Pattern ENV_KEY = Pattern.compile("[^A-Z0-9_]");
    private static final int MAX_NAME_LEN = 64;
    private static final int HASH_LEN = 8;

    private SwarmConfigNaming() {
    }

    static String normalizeConfigPath(String raw) {
        return normalizeRepoRelative(raw, "Config path");
    }

    static String normalizeFileGlob(String raw) {
        if (raw == null || raw.isBlank()) {
            return "**/*";
        }
        return raw.trim();
    }

    static String basenameFromRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Config file path is required.");
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        String withoutExt = stripExtension(normalized.replace('/', '-'));
        return sanitizeBasename(withoutExt);
    }

    static String hash8(byte[] content) {
        if (content == null) {
            content = new byte[0];
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(HASH_LEN);
            for (int i = 0; i < HASH_LEN; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String configName(String basename, byte[] content) {
        String base = sanitizeBasename(basename);
        String hash = hash8(content);
        String name = base + "-" + hash;
        if (name.length() <= MAX_NAME_LEN) {
            return name;
        }
        int maxBase = MAX_NAME_LEN - 1 - hash.length();
        if (maxBase < 1) {
            return hash.substring(0, Math.min(MAX_NAME_LEN, hash.length()));
        }
        return base.substring(0, maxBase) + "-" + hash;
    }

    static String envKeyForBasename(String basename) {
        String key = sanitizeBasename(basename).toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        key = ENV_KEY.matcher(key).replaceAll("_");
        while (key.contains("__")) {
            key = key.replace("__", "_");
        }
        key = stripSurroundingUnderscores(key);
        return key.isEmpty() ? "CONFIG" : key;
    }

    static List<String> parseSecretKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Secret keys are required (one Vault KV key per line).");
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String key = line.trim();
            if (key.isEmpty() || key.startsWith("#")) {
                continue;
            }
            if (key.contains("=")) {
                throw new IllegalArgumentException(
                        "Secret keys must be Vault KV names, one per line "
                                + "(not KEY=${KEY} — that belongs in the Stack env field).");
            }
            if (out.contains(key)) {
                throw new IllegalArgumentException("Duplicate secret key: " + key);
            }
            out.add(key);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Secret keys are required (one Vault KV key per line).");
        }
        return List.copyOf(out);
    }

    static String labelGitSha(String repositoryReference) {
        if (repositoryReference == null || repositoryReference.isBlank()) {
            return "";
        }
        String ref = repositoryReference.trim();
        if (ref.startsWith("refs/heads/")) {
            ref = ref.substring("refs/heads/".length());
        } else if (ref.startsWith("refs/tags/")) {
            ref = ref.substring("refs/tags/".length());
        }
        if (ref.length() > 40) {
            return ref.substring(0, 40);
        }
        return ref;
    }

    static String sanitizeBasename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "config";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9._-]+", "-");
        s = s.replaceAll("-{2,}", "-");
        s = stripSurroundingDotsAndDashes(s);
        if (s.isEmpty()) {
            return "config";
        }
        if (!Character.isLetterOrDigit(s.charAt(0))) {
            s = "c-" + s;
        }
        return s;
    }

    /** Strip leading/trailing {@code _} without regex. */
    private static String stripSurroundingUnderscores(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    /** Strip leading/trailing {@code .} and {@code -} without regex. */
    private static String stripSurroundingDotsAndDashes(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            char c = value.charAt(start);
            if (c != '.' && c != '-') {
                break;
            }
            start++;
        }
        while (end > start) {
            char c = value.charAt(end - 1);
            if (c != '.' && c != '-') {
                break;
            }
            end--;
        }
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static String stripExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot > 0) {
            return path.substring(0, dot);
        }
        return path;
    }

    private static String normalizeRepoRelative(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        String path = raw.trim().replace('\\', '/');
        if (path.isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException(label + " must be relative to the repository root.");
        }
        if (path.contains("\0")) {
            throw new IllegalArgumentException(label + " is invalid.");
        }
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(label + " must not contain empty segments or '..'.");
            }
        }
        return path;
    }
}
