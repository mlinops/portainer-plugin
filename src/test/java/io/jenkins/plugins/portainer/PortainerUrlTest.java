package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class PortainerUrlTest {

    @Test
    public void normalize_stripsTrailingSlashAndPath() {
        assertEquals(
                "https://portainer.example:9443",
                PortainerUrl.normalizeBaseUrl("https://portainer.example:9443/api/"));
    }

    @Test
    public void normalizeSyntaxOnly_doesNotRequireDns() {
        assertEquals(
                "https://no-such-host-portainer-port9.invalid:9443",
                PortainerUrl.normalizeBaseUrlSyntaxOnly(
                        "https://no-such-host-portainer-port9.invalid:9443/api/"));
    }

    @Test
    public void normalize_rejectsMissingScheme() {
        try {
            PortainerUrl.normalizeBaseUrl("portainer.example:9443");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("http"));
        }
    }

    @Test
    public void normalize_rejectsUserInfo() {
        try {
            PortainerUrl.normalizeBaseUrl("https://user:pass@portainer.example:9443");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("userinfo"));
        }
    }

    @Test
    public void normalize_rejectsBlank() {
        try {
            PortainerUrl.normalizeBaseUrl("  ");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("required"));
        }
    }
}
