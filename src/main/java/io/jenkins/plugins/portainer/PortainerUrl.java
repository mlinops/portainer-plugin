package io.jenkins.plugins.portainer;

/**
 * Parses and validates a Portainer base URL (scheme, no userinfo, host allowlist).
 */
final class PortainerUrl {

    private PortainerUrl() {
    }

    /**
     * Syntax-only normalize (scheme/host/userinfo). Does <strong>not</strong> resolve DNS
     * or run the SSRF host allowlist — use for form checks so System stays responsive.
     *
     * @return normalized base URL without trailing slash (e.g. {@code https://portainer.example:9443})
     */
    static String normalizeBaseUrlSyntaxOnly(String portainerUrl) {
        if (portainerUrl == null || portainerUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Portainer URL is required (http:// or https://portainer.example:9443).");
        }
        String clean = portainerUrl.trim().replaceAll("/+$", "");
        boolean https = clean.regionMatches(true, 0, "https://", 0, 8);
        boolean http = clean.regionMatches(true, 0, "http://", 0, 7);
        if (!https && !http) {
            throw new IllegalArgumentException(
                    "Portainer URL must start with http:// or https://.");
        }
        int schemeEnd = clean.indexOf("://");
        int authStart = schemeEnd + 3;
        int at = clean.indexOf('@', authStart);
        int pathStart = clean.indexOf('/', authStart);
        if (at >= 0 && (pathStart < 0 || at < pathStart)) {
            throw new IllegalArgumentException(
                    "Portainer URL must not contain userinfo (user:pass@host); use Credentials for the API key.");
        }
        if (pathStart == authStart) {
            throw new IllegalArgumentException("Portainer URL host is missing.");
        }
        // Base only — strip any path/query (API paths are appended by the client)
        return pathStart > 0 ? clean.substring(0, pathStart) : clean;
    }

    /**
     * Full normalize: syntax plus SSRF host allowlist / DNS (for runtime probe / preflight).
     *
     * @return normalized base URL without trailing slash (e.g. {@code https://portainer.example:9443})
     */
    static String normalizeBaseUrl(String portainerUrl) {
        String base = normalizeBaseUrlSyntaxOnly(portainerUrl);
        ConnectionTester.assertHostAllowed(base);
        return base;
    }
}
