package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class VaultUrlTest {

    @Test
    public void normalize_stripsTrailingSlashAndPath() {
        assertEquals(
                "https://vault.example:8200",
                VaultUrl.normalizeBaseUrlSyntaxOnly("https://vault.example:8200/v1/"));
    }

    @Test
    public void normalize_rejectsUserInfo() {
        try {
            VaultUrl.normalizeBaseUrlSyntaxOnly("https://user:pass@vault.example:8200");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("userinfo"));
        }
    }

    @Test
    public void normalize_rejectsMissingScheme() {
        try {
            VaultUrl.normalizeBaseUrlSyntaxOnly("vault.example:8200");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("http"));
        }
    }
}
