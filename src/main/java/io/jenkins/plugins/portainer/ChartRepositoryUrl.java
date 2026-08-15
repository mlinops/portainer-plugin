package io.jenkins.plugins.portainer;

/**
 * Validates Helm chart repository URLs passed to Portainer ({@code http}/{@code https}, no userinfo).
 * Portainer fetches the chart; this plugin never downloads chart content itself.
 */
final class ChartRepositoryUrl {

    private ChartRepositoryUrl() {
    }

    /**
     * @return trimmed URL
     */
    static String normalize(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Chart repository URL is required (https://charts.example/helm).");
        }
        String clean = repositoryUrl.trim();
        boolean https = clean.regionMatches(true, 0, "https://", 0, 8);
        boolean http = clean.regionMatches(true, 0, "http://", 0, 7);
        if (!https && !http) {
            throw new IllegalArgumentException("Chart repository URL must start with http:// or https://.");
        }
        int schemeEnd = clean.indexOf("://");
        int authStart = schemeEnd + 3;
        int at = clean.indexOf('@', authStart);
        int pathStart = clean.indexOf('/', authStart);
        if (at >= 0 && (pathStart < 0 || at < pathStart)) {
            throw new IllegalArgumentException(
                    "Chart repository URL must not contain userinfo (user:pass@host).");
        }
        if (pathStart == authStart) {
            throw new IllegalArgumentException("Chart repository URL host is missing.");
        }
        // Format-only SSRF host check (DNS deferred to Portainer / runtime).
        try {
            ConnectionTester.assertHostAllowed(clean, ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Chart repository URL rejected: " + e.getMessage(), e);
        }
        return clean;
    }
}
