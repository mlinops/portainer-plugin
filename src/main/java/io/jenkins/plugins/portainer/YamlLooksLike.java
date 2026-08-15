package io.jenkins.plugins.portainer;

/** Soft YAML sniff for Manifest content and Helm values (Portainer applies the real check). */
final class YamlLooksLike {

    private YamlLooksLike() {
    }

    static void require(String content, String blankMessage, String notYamlMessage) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(blankMessage);
        }
        String trimmed = content.trim();
        if (!(trimmed.contains(":") || trimmed.startsWith("---"))) {
            throw new IllegalArgumentException(notYamlMessage);
        }
    }
}
