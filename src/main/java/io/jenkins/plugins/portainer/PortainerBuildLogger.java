package io.jenkins.plugins.portainer;

import hudson.model.TaskListener;

import java.io.PrintStream;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dual-sink build logging: INFO/WARN/ERROR go to the build console and JUL; DEBUG/FINE goes to JUL
 * and optionally to the console when {@code verboseLogging} is on.
 * <p>
 * Console contract:
 * <ul>
 *   <li>Banner: leading blank line, then {@code ======== Portainer … ========}; no blank after;
 *       no closing bar</li>
 *   <li>Body: {@code [LEVEL] } + capitalized message; no {@code Portainer:} on every line</li>
 *   <li>Phase order per connection: Portainer (dump + preflight) → Git (if used) →
 *       Vault (if used) → mutate → Summary. Dump attrs immediately before that preflight</li>
 *   <li>Connection dumps are separate DEBUG lines ({@code Connection=} / {@code Vault} /
 *       {@code Git}) — never mix Portainer+Vault+Git on one line</li>
 *   <li>Markers lowercase in parens: {@code (skipped)} / {@code (created)} / {@code (exists)} /
 *       {@code (missing)}</li>
 *   <li>Preflight: {@code Preflight check of Vault|Git|endpoint N (name)}</li>
 *   <li>INFO = counts / outcomes; DEBUG (verbose) = names, hashes, exists/missing, env lists</li>
 *   <li>Soft prune → {@code [WARN]}; successful prune → {@code [INFO] Pruned: …}</li>
 *   <li>One {@code [ERROR]} inside the frame; Freestyle without second {@code ERROR: Portainer:}
 *       ({@link PortainerLoggedAbort})</li>
 *   <li>Never log secret values, tokens, or passwords</li>
 * </ul>
 */
final class PortainerBuildLogger {

    static final String PREFIX = "Portainer:";

    static final String TITLE_STACK = "Portainer Stack Deployment";
    static final String TITLE_STACK_CONFIG = "Portainer Stack Config";
    static final String TITLE_STACK_SECRET = "Portainer Stack Secret";
    static final String TITLE_MANIFEST = "Portainer Manifest Deployment";
    static final String TITLE_HELM = "Portainer Helm Deployment";

    private static final String BANNER_SIDE = "========";

    private final Logger jul;
    private final TaskListener listener;
    private final boolean verbose;

    private boolean opened;
    private boolean closed;
    private boolean errorEmitted;

    PortainerBuildLogger(Logger jul, TaskListener listener, boolean verbose) {
        this.jul = jul == null ? Logger.getLogger(PortainerBuildLogger.class.getName()) : jul;
        this.listener = listener;
        this.verbose = verbose;
    }

    boolean isVerbose() {
        return verbose;
    }

    TaskListener getListener() {
        return listener;
    }

    boolean hasLoggedError() {
        return errorEmitted;
    }

    void open(String title) {
        if (opened) {
            return;
        }
        opened = true;
        closed = false;
        printlnRaw("");
        printlnRaw(bannerHeader(title));
    }

    void close() {
        if (!opened || closed) {
            return;
        }
        closed = true;
    }

    void info(String message) {
        emit(Level.INFO, message, true);
    }

    void warn(String message) {
        emit(Level.WARNING, message, true);
    }

    /**
     * DEBUG/FINE: always JUL; console only when verbose.
     */
    void debug(String message) {
        emit(Level.FINE, message, verbose);
    }

    /**
     * HTTP timing line: {@code GET /api/status (42ms)} or with note
     * {@code GET /api/stacks (88ms) — find by name}.
     */
    void http(String method, String path, long durationMs) {
        http(method, path, durationMs, null);
    }

    void http(String method, String path, long durationMs, String note) {
        String m = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        String p = path == null || path.isBlank() ? "/" : path.trim();
        StringBuilder sb = new StringBuilder(m).append(' ').append(p)
                .append(" (").append(Math.max(0L, durationMs)).append("ms)");
        if (note != null && !note.isBlank()) {
            sb.append(" — ").append(note.trim());
        }
        debug(sb.toString());
    }

    /**
     * Console + JUL SEVERE without stack (message body only). Idempotent per logger instance.
     */
    void error(String message) {
        if (errorEmitted) {
            return;
        }
        errorEmitted = true;
        emit(Level.SEVERE, message, true);
    }

    /**
     * Console summary + stack trace, and JUL SEVERE with stack. Use when operators need full
     * failure context (e.g. Helm); callers still throw {@link hudson.AbortException} for Jenkins.
     */
    void error(String message, Throwable thrown) {
        error(message);
        if (thrown != null) {
            if (jul.isLoggable(Level.SEVERE)) {
                jul.log(Level.SEVERE, formatLine(Level.SEVERE, firstLine(message)), thrown);
            }
            if (listener != null) {
                thrown.printStackTrace(listener.getLogger());
            }
        }
    }

    /**
     * JUL SEVERE with stack; no console line (caller typically throws {@link hudson.AbortException}
     * so Jenkins prints the summary once).
     */
    void errorJul(String message, Throwable thrown) {
        if (!jul.isLoggable(Level.SEVERE)) {
            return;
        }
        String line = formatLine(Level.SEVERE, firstLine(message));
        if (thrown == null) {
            jul.log(Level.SEVERE, line);
        } else {
            jul.log(Level.SEVERE, line, thrown);
        }
    }

    static String formatDuration(long elapsedMs) {
        long ms = Math.max(0L, elapsedMs);
        if (ms < 1000L) {
            return ms + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0d);
    }

    /**
     * Success completion line: {@code Summary key=value …}.
     * Omits null/blank values; never put secrets in {@code fields}.
     */
    void summary(Map<String, String> fields) {
        info(formatSummary(fields));
    }

    /**
     * Appends {@code duration} from {@code startedNs} ({@link System#nanoTime()}) and emits
     * {@link #summary(Map)}. Mutates {@code fields} when non-null.
     */
    void summaryWithDuration(long startedNs, Map<String, String> fields) {
        Map<String, String> out = fields == null ? summaryFields() : fields;
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        out.put("duration", formatDuration(elapsedMs));
        summary(out);
    }

    /**
     * Builds the message body (no level prefix) for a success summary line.
     * Iteration order of {@code fields} is preserved (prefer {@link LinkedHashMap}).
     */
    static String formatSummary(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("Summary");
        if (fields == null || fields.isEmpty()) {
            return sb.toString();
        }
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            sb.append(' ').append(key.trim()).append('=').append(value.trim());
        }
        return sb.toString();
    }

    /**
     * Host only from an http(s) URL for summary lines (never userinfo / path / query).
     *
     * @return host, or {@code null} if missing / unparseable
     */
    static String repoHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Mutable ordered field bag for {@link #summary(Map)}. */
    static LinkedHashMap<String, String> summaryFields() {
        return new LinkedHashMap<>();
    }

    /** {@code Preflight check of endpoint 319 (srv-crm-t01)} or without name. */
    static String formatPreflightEndpoint(int endpointId, String endpointName) {
        String name = endpointName == null ? "" : endpointName.trim();
        if (name.isEmpty()) {
            return "Preflight check of endpoint " + endpointId;
        }
        return "Preflight check of endpoint " + endpointId + " (" + name + ")";
    }

    /**
     * {@code Vault path=applications/example/systems/rabbitmq} or with
     * {@code version=3} when a specific KV version is requested.
     */
    static String formatVaultPath(String path, Integer version) {
        String p = path == null ? "" : path.trim();
        if (version == null) {
            return "Vault path=" + p;
        }
        return "Vault path=" + p + " version=" + version;
    }

    /** {@code Connection=Production Portainer mode=inherit} — Portainer job connection dump. */
    static String formatPortainerConnection(ResolvedConnection connection) {
        String name = connection == null || connection.displayName == null
                ? ""
                : connection.displayName.trim();
        String mode = connection == null || connection.mode == null
                ? ""
                : connection.mode.trim();
        return "Connection=" + name + " mode=" + mode;
    }

    /**
     * DEBUG dump at Portainer phase start: {@code Connection=} then {@code endpointId=}
     * plus optional trailing attrs (e.g. {@code prune=false pullImage=false}).
     */
    static void debugPortainerStart(
            PortainerBuildLogger log,
            ResolvedConnection connection,
            int endpointId,
            String endpointExtras) {
        if (log == null) {
            return;
        }
        log.debug(formatPortainerConnection(connection));
        String extras = endpointExtras == null ? "" : endpointExtras.trim();
        if (extras.isEmpty()) {
            log.debug("endpointId=" + endpointId);
        } else {
            log.debug("endpointId=" + endpointId + " " + extras);
        }
    }

    /**
     * {@code Vault mode=inherit path=… mount=secret} or with {@code version=3}.
     * Omit blank path/mount; omit version when null (latest).
     */
    static String formatVaultConnection(String mode, String path, String mount, Integer version) {
        StringBuilder sb = new StringBuilder("Vault mode=")
                .append(mode == null ? "" : mode.trim());
        appendField(sb, "path", path);
        appendField(sb, "mount", mount);
        if (version != null) {
            sb.append(" version=").append(version);
        }
        return sb.toString();
    }

    /**
     * {@code Git ref=… repo=…} plus optional {@code path} / {@code fileGlob} /
     * {@code compose} / {@code manifest}. Blank fields are omitted.
     */
    static String formatGitConnection(
            String ref,
            String repo,
            String path,
            String fileGlob,
            String compose,
            String manifest) {
        StringBuilder sb = new StringBuilder("Git");
        appendField(sb, "ref", ref);
        appendField(sb, "repo", repo);
        appendField(sb, "path", path);
        appendField(sb, "fileGlob", fileGlob);
        appendField(sb, "compose", compose);
        appendField(sb, "manifest", manifest);
        return sb.toString();
    }

    /** INFO preflight line + DEBUG {@link #formatGitConnection} dump (clone vs remote-git stay in steps). */
    static void logGitPreflight(
            PortainerBuildLogger log,
            String ref,
            String repo,
            String path,
            String fileGlob,
            String compose,
            String manifest) {
        log.info("Preflight check of Git");
        log.debug(formatGitConnection(ref, repo, path, fileGlob, compose, manifest));
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(' ').append(key).append('=').append(value.trim());
    }

    /** {@code Keys differ: missing=1 extra=1} */
    static String formatKeysDiffer(int missing, int extra) {
        return "Keys differ: missing=" + Math.max(0, missing) + " extra=" + Math.max(0, extra);
    }

    /** {@code Hash of basename=hash} */
    static String formatHashOf(String basename, String hash) {
        String base = basename == null ? "" : basename.trim();
        String h = hash == null ? "" : hash.trim();
        return "Hash of " + base + "=" + h;
    }

    /** Comma-separated names, or {@code (none)} when empty. */
    static String formatNameList(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(name.trim());
        }
        return first ? "(none)" : sb.toString();
    }

    /**
     * Capitalize the first letter of the message body (after stripping a legacy {@code Portainer:}
     * prefix). Leaves markers like {@code (skipped)} and already-capitalized / non-letter starts unchanged.
     */
    static String capitalizeMessage(String message) {
        String body = stripPortainerPrefix(message);
        if (body.isEmpty()) {
            return body;
        }
        char c = body.charAt(0);
        if (Character.isLetter(c) && Character.isLowerCase(c)) {
            return Character.toUpperCase(c) + body.substring(1);
        }
        return body;
    }

    /**
     * Path (+ optional query) safe for DEBUG: never includes userinfo; strips long query values.
     */
    static String safeRequestPath(java.net.URI uri) {
        if (uri == null) {
            return "/";
        }
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return path;
    }

    static String bannerHeader(String title) {
        String t = title == null || title.isBlank() ? "Portainer" : title.trim();
        return BANNER_SIDE + " " + t + " " + BANNER_SIDE;
    }

    static String consoleLabel(Level level) {
        if (level == null) {
            return "INFO";
        }
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (level.intValue() < Level.INFO.intValue()) {
            return "DEBUG";
        }
        return "INFO";
    }

    static String stripPortainerPrefix(String message) {
        String body = message == null ? "" : message.trim();
        if (body.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            body = body.substring(PREFIX.length()).trim();
        }
        return body;
    }

    static String formatLine(Level level, String message) {
        return "[" + consoleLabel(level) + "] " + capitalizeMessage(message);
    }

    private void emit(Level level, String message, boolean toConsole) {
        boolean writeConsole = toConsole && listener != null;
        boolean logJul = jul.isLoggable(level);
        if (!logJul && !writeConsole) {
            return;
        }
        String body = capitalizeMessage(message);
        String[] lines = body.isEmpty() ? new String[] {""} : body.split("\\R", -1);
        String first = formatLine(level, lines[0]);
        if (logJul) {
            jul.log(level, first);
        }
        if (writeConsole) {
            PrintStream out = listener.getLogger();
            out.println(first);
            for (int i = 1; i < lines.length; i++) {
                out.println(lines[i]);
            }
        }
    }

    private static String firstLine(String message) {
        String body = capitalizeMessage(message);
        int nl = indexOfNewline(body);
        return nl < 0 ? body : body.substring(0, nl);
    }

    private static int indexOfNewline(String body) {
        int cr = body.indexOf('\r');
        int lf = body.indexOf('\n');
        if (cr < 0) {
            return lf;
        }
        if (lf < 0) {
            return cr;
        }
        return Math.min(cr, lf);
    }

    private void printlnRaw(String line) {
        if (listener == null) {
            return;
        }
        listener.getLogger().println(line);
    }
}
