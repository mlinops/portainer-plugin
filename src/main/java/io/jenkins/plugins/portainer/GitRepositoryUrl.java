package io.jenkins.plugins.portainer;

/**
 * Validates Git repository URLs for Portainer stack deploy (http/https, no userinfo).
 */
final class GitRepositoryUrl {

    private GitRepositoryUrl() {
    }

    /**
     * @return trimmed URL without trailing slash on the host-only form; path preserved
     */
    static String normalize(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository URL is required (https://gitlab.example/group/stack.git).");
        }
        String clean = repositoryUrl.trim();
        boolean https = clean.regionMatches(true, 0, "https://", 0, 8);
        boolean http = clean.regionMatches(true, 0, "http://", 0, 7);
        if (!https && !http) {
            throw new IllegalArgumentException("Repository URL must start with http:// or https://.");
        }
        int schemeEnd = clean.indexOf("://");
        int authStart = schemeEnd + 3;
        int at = clean.indexOf('@', authStart);
        int pathStart = clean.indexOf('/', authStart);
        if (at >= 0 && (pathStart < 0 || at < pathStart)) {
            throw new IllegalArgumentException(
                    "Repository URL must not contain userinfo (user:pass@host); use Git credentials instead.");
        }
        if (pathStart == authStart) {
            throw new IllegalArgumentException("Repository URL host is missing.");
        }
        return clean;
    }
}
