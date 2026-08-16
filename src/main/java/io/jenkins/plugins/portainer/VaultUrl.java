package io.jenkins.plugins.portainer;

/**
 * Parses and validates a HashiCorp Vault base URL (scheme, no userinfo, host allowlist).
 */
final class VaultUrl {

    private VaultUrl() {
    }

    /**
     * Syntax-only normalize (scheme/host/userinfo). Does <strong>not</strong> resolve DNS
     * or run the SSRF host allowlist — use for form checks.
     *
     * @return normalized base URL without trailing slash (e.g. {@code https://vault.example:8200})
     */
    static String normalizeBaseUrlSyntaxOnly(String vaultUrl) {
        if (vaultUrl == null || vaultUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Vault URL is required (http:// or https://vault.example:8200).");
        }
        String clean = VaultClient.stripTrailingSlashes(vaultUrl.trim());
        boolean https = clean.regionMatches(true, 0, "https://", 0, 8);
        boolean http = clean.regionMatches(true, 0, "http://", 0, 7);
        if (!https && !http) {
            throw new IllegalArgumentException(
                    "Vault URL must start with http:// or https://.");
        }
        int schemeEnd = clean.indexOf("://");
        int authStart = schemeEnd + 3;
        int at = clean.indexOf('@', authStart);
        int pathStart = clean.indexOf('/', authStart);
        if (at >= 0 && (pathStart < 0 || at < pathStart)) {
            throw new IllegalArgumentException(
                    "Vault URL must not contain userinfo (user:pass@host); use Credentials for AppRole.");
        }
        if (pathStart == authStart) {
            throw new IllegalArgumentException("Vault URL host is missing.");
        }
        return pathStart > 0 ? clean.substring(0, pathStart) : clean;
    }

    /**
     * Full normalize: syntax plus SSRF host allowlist / DNS (for runtime Vault calls).
     *
     * @return normalized base URL without trailing slash (e.g. {@code https://vault.example:8200})
     */
    static String normalizeBaseUrl(String vaultUrl) {
        String base = normalizeBaseUrlSyntaxOnly(vaultUrl);
        ConnectionTester.assertHostAllowed(base);
        return base;
    }
}
