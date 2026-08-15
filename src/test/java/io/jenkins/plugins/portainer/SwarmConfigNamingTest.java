package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmConfigNamingTest {

    @Test
    void hash8_isLowerHexFromFirstEightBytes() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String hash = SwarmConfigNaming.hash8(content);
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]{16}"));
        assertEquals("2cf24dba5fb0a30e", hash);
    }

    @Test
    void basenameFromRelativePath_stripsExtensionAndSlashes() {
        assertEquals("app-settings", SwarmConfigNaming.basenameFromRelativePath("app-settings.json"));
        assertEquals("subdir-nginx", SwarmConfigNaming.basenameFromRelativePath("subdir/nginx.conf"));
    }

    @Test
    void configName_combinesBasenameAndHash() {
        byte[] content = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String name = SwarmConfigNaming.configName("app-settings", content);
        assertEquals("app-settings-" + SwarmConfigNaming.hash8(content), name);
        assertTrue(name.startsWith("app-settings-"));
        assertEquals(16, name.substring("app-settings-".length()).length());
    }

    @Test
    void envKeyForBasename_uppercasesHyphensNoSuffix() {
        assertEquals(
                "RABBITMQ_CONFIG",
                SwarmConfigNaming.envKeyForBasename(
                        SwarmConfigNaming.basenameFromRelativePath("rabbitmq-config.json")));
        assertEquals("APP_SETTINGS", SwarmConfigNaming.envKeyForBasename("app-settings"));
        assertEquals("NGINX", SwarmConfigNaming.envKeyForBasename("nginx"));
        assertEquals("STAGE_RABBITMQ_CONFIG", SwarmConfigNaming.envKeyForBasename("stage_rabbitmq_config"));
        assertEquals("ENABLED_PLUGINS", SwarmConfigNaming.envKeyForBasename("enabled_plugins"));
        assertEquals("RABBITMQ_SIGNING_KEY", SwarmConfigNaming.envKeyForBasename("rabbitmq_signing_key"));
    }

    @Test
    void parseSecretKeys_skipsCommentsAndBlanks() {
        assertEquals(
                List.of("rabbitmq_signing_key", "erlang_cookie"),
                SwarmConfigNaming.parseSecretKeys("rabbitmq_signing_key\n# ignore\n\nerlang_cookie\n"));
    }

    @Test
    void parseSecretKeys_rejectsDuplicateAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> SwarmConfigNaming.parseSecretKeys("a\na"));
        assertThrows(IllegalArgumentException.class, () -> SwarmConfigNaming.parseSecretKeys("\n# only\n"));
        assertThrows(IllegalArgumentException.class, () -> SwarmConfigNaming.parseSecretKeys(""));
        IllegalArgumentException eq = assertThrows(
                IllegalArgumentException.class,
                () -> SwarmConfigNaming.parseSecretKeys("RABBITMQ_ERLANG_COOKIE=${RABBITMQ_ERLANG_COOKIE}"));
        assertTrue(eq.getMessage().contains("KEY=${KEY}"));
    }

    @Test
    void normalizeConfigPath_rejectsAbsolute() {
        try {
            SwarmConfigNaming.normalizeConfigPath("/etc/passwd");
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("relative"));
        }
    }
}
