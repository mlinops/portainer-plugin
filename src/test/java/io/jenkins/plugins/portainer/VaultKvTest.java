package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultKvTest {

    @Test
    void optionalSoftSkip_noneMode_returnsNull() throws Exception {
        Map<String, String> data = VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.NONE,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog()));
        assertNull(data);
    }

    @Test
    void required_noneMode_aborts() {
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.NONE,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault connection is required"));
    }

    @Test
    void optionalSoftSkip_emptyPath_returnsNull() throws Exception {
        VaultFields fields = VaultFields.parse("", "secret", null, null, null, null);
        Map<String, String> data = VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.INHERIT,
                fields,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog()));
        assertNull(data);
    }

    @Test
    void optionalSoftSkip_manualPartialWithoutPath_aborts() {
        VaultFields fields = VaultFields.parse(
                "", "secret", null, null, "https://vault.example", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                ConnectionMode.MANUAL,
                fields,
                "approle-cred",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("partially configured"));
    }

    @Test
    void required_blankPath_aborts() {
        VaultFields fields = VaultFields.parse("", "secret", null, null, null, null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.INHERIT,
                fields,
                null,
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault path is required"));
    }

    @Test
    void required_manualMissingCreds_aborts() {
        VaultFields fields = VaultFields.parse(
                "apps/rabbitmq", "secret", null, null, "https://vault.example", null);
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(new VaultKv.Request(
                VaultKv.Policy.REQUIRED,
                ConnectionMode.MANUAL,
                fields,
                "",
                null,
                null,
                null,
                0,
                0,
                quietLog())));
        assertTrue(ex.getMessage().contains("Vault Manual requires vaultUrl"));
    }

    @Test
    void nullRequest_aborts() {
        AbortException ex = assertThrows(AbortException.class, () -> VaultKv.resolve(null));
        assertTrue(ex.getMessage().contains("build logger"));
    }

    private static PortainerBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new PortainerBuildLogger(Logger.getLogger("VaultKvTest"), listener, false);
    }
}
