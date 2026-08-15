package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlLooksLikeTest {

    @Test
    void require_acceptsColonAndDocumentMarker() {
        assertDoesNotThrow(() -> YamlLooksLike.require("key: value", "blank", "not-yaml"));
        assertDoesNotThrow(() -> YamlLooksLike.require("---\nfoo", "blank", "not-yaml"));
    }

    @Test
    void require_rejectsBlankAndNonYaml() {
        assertThrows(IllegalArgumentException.class, () -> YamlLooksLike.require(null, "blank", "not-yaml"));
        assertThrows(IllegalArgumentException.class, () -> YamlLooksLike.require("  ", "blank", "not-yaml"));
        assertThrows(IllegalArgumentException.class, () -> YamlLooksLike.require("plain text", "blank", "not-yaml"));
    }
}
