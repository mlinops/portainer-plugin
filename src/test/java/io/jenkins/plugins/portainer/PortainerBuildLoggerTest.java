package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortainerBuildLoggerTest {

    @Test
    void formatDuration_msAndSeconds() {
        assertEquals("0ms", PortainerBuildLogger.formatDuration(0));
        assertEquals("42ms", PortainerBuildLogger.formatDuration(42));
        assertEquals("1.0s", PortainerBuildLogger.formatDuration(1000));
        assertEquals("1.2s", PortainerBuildLogger.formatDuration(1200));
    }

    @Test
    void verboseFlag_controlsDebugConsoleEligibility() {
        PortainerBuildLogger quiet = new PortainerBuildLogger(
                Logger.getLogger("test"), null, false);
        PortainerBuildLogger noisy = new PortainerBuildLogger(
                Logger.getLogger("test"), null, true);
        assertFalse(quiet.isVerbose());
        assertTrue(noisy.isVerbose());
    }

    @Test
    void formatLine_usesBracketLevelsAndStripsLegacyPrefix() {
        assertEquals("[INFO] Hi", PortainerBuildLogger.formatLine(Level.INFO, "hi"));
        assertEquals("[WARN] Hi", PortainerBuildLogger.formatLine(Level.WARNING, "hi"));
        assertEquals("[ERROR] Hi", PortainerBuildLogger.formatLine(Level.SEVERE, "hi"));
        assertEquals("[DEBUG] Hi", PortainerBuildLogger.formatLine(Level.FINE, "hi"));
        assertEquals("[INFO] Hi", PortainerBuildLogger.formatLine(Level.INFO, "Portainer: hi"));
        assertEquals("[INFO] (skipped) x", PortainerBuildLogger.formatLine(Level.INFO, "(skipped) x"));
        assertEquals(
                "Preflight check of endpoint 319 (srv)",
                PortainerBuildLogger.formatPreflightEndpoint(319, "srv"));
        assertEquals("Vault path=applications/example/systems/rabbitmq",
                PortainerBuildLogger.formatVaultPath("applications/example/systems/rabbitmq", null));
        assertEquals("Vault path=applications/example/systems/rabbitmq version=3",
                PortainerBuildLogger.formatVaultPath("applications/example/systems/rabbitmq", 3));
        assertEquals(
                "Connection=Production Portainer mode=inherit",
                PortainerBuildLogger.formatPortainerConnection(
                        new ResolvedConnection("Production Portainer", "inherit",
                                "https://portainer.example", "cred", 10_000, 30_000)));
        assertEquals(
                "Vault mode=inherit path=apps/rabbitmq mount=secret version=3",
                PortainerBuildLogger.formatVaultConnection(
                        "inherit", "apps/rabbitmq", "secret", 3));
        assertEquals(
                "Git ref=main repo=https://gitlab.example/stack.git path=configs fileGlob=**/rabbitmq*",
                PortainerBuildLogger.formatGitConnection(
                        "main",
                        "https://gitlab.example/stack.git",
                        "configs",
                        "**/rabbitmq*",
                        null,
                        null));
        assertEquals("Keys differ: missing=1 extra=2", PortainerBuildLogger.formatKeysDiffer(1, 2));
        assertEquals("Hash of rabbitmq_config=abc", PortainerBuildLogger.formatHashOf("rabbitmq_config", "abc"));
    }

    @Test
    void console_bannerLevelsAndMultilineError() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8)) {
            PortainerBuildLogger log = new PortainerBuildLogger(Logger.getLogger("test"), listener, false);
            log.open(PortainerBuildLogger.TITLE_STACK_SECRET);
            log.info("endpointId=319 keys=3");
            log.warn("Vault Inherit: vaultVersion is ignored");
            log.debug("GET /api/status (1ms)");
            log.error("stack operation failed: HTTP 500 - first\nsecond line");
            log.close();
        }
        String out = buf.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String header = PortainerBuildLogger.bannerHeader(PortainerBuildLogger.TITLE_STACK_SECRET);
        assertTrue(out.startsWith("\n" + header + "\n[INFO]"));
        assertTrue(out.contains(header));
        assertTrue(out.contains("[INFO] EndpointId=319 keys=3"));
        assertTrue(out.contains("[WARN] Vault Inherit: vaultVersion is ignored"));
        assertFalse(out.contains("[DEBUG]"));
        assertTrue(out.contains("[ERROR] Stack operation failed: HTTP 500 - first\nsecond line"));
        assertFalse(out.lines().anyMatch(line -> !line.isEmpty() && line.chars().allMatch(c -> c == '=')));
        assertFalse(out.contains("Portainer: endpointId"));
    }

    @Test
    void errorWithThrowable_stillPrintsStackToConsoleWhenJulOff() throws Exception {
        Logger jul = Logger.getLogger("PortainerBuildLoggerTest.errorThrowable");
        Level previous = jul.getLevel();
        jul.setLevel(Level.OFF);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8)) {
            PortainerBuildLogger log = new PortainerBuildLogger(jul, listener, false);
            log.error("helm failed", new RuntimeException("boom-detail"));
        } finally {
            jul.setLevel(previous);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("[ERROR] Helm failed"));
        assertTrue(out.contains("boom-detail"));
        assertTrue(out.contains("RuntimeException"));
    }

    @Test
    void console_consecutiveSteps_openingBannerOnly() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8)) {
            PortainerBuildLogger secret = new PortainerBuildLogger(Logger.getLogger("test"), listener, false);
            secret.open(PortainerBuildLogger.TITLE_STACK_SECRET);
            secret.info("keys=3");
            secret.close();
            PortainerBuildLogger config = new PortainerBuildLogger(Logger.getLogger("test"), listener, false);
            config.open(PortainerBuildLogger.TITLE_STACK_CONFIG);
            config.info("path=configs/swarm");
            config.close();
        }
        String out = buf.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        String expected = "\n"
                + PortainerBuildLogger.bannerHeader(PortainerBuildLogger.TITLE_STACK_SECRET)
                + "\n[INFO] Keys=3\n"
                + "\n"
                + PortainerBuildLogger.bannerHeader(PortainerBuildLogger.TITLE_STACK_CONFIG)
                + "\n[INFO] Path=configs/swarm\n";
        assertEquals(expected, out);
    }

    @Test
    void verbose_writesDebugToConsole() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8)) {
            PortainerBuildLogger log = new PortainerBuildLogger(Logger.getLogger("test"), listener, true);
            log.open(PortainerBuildLogger.TITLE_STACK);
            log.debug("GET /api/status (1ms)");
            log.close();
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("[DEBUG] GET /api/status (1ms)"));
    }

    @Test
    void abort_consoleErrorWithoutPortainerPrefix() {
        PortainerBuildLogger log = new PortainerBuildLogger(Logger.getLogger("test"), null, false);
        AbortException ex = PortainerConnections.abort(log, "vault path not found");
        assertTrue(ex instanceof PortainerLoggedAbort);
        assertEquals("vault path not found", ex.getMessage());
        assertTrue(log.hasLoggedError());
        AbortException again = PortainerConnections.abort(log, "other");
        assertTrue(again instanceof PortainerLoggedAbort);
    }

    @Test
    void summaryWithDuration_appendsDuration() {
        PortainerBuildLogger log = new PortainerBuildLogger(
                java.util.logging.Logger.getLogger("test"), TaskListener.NULL, false);
        LinkedHashMap<String, String> fields = PortainerBuildLogger.summaryFields();
        fields.put("outcome", "validated");
        long started = System.nanoTime() - 50_000_000L;
        log.summaryWithDuration(started, fields);
        assertTrue(fields.containsKey("duration"));
        assertFalse(fields.get("duration").isBlank());
        assertEquals(
                "Summary outcome=validated duration=" + fields.get("duration"),
                PortainerBuildLogger.formatSummary(fields));
    }

    @Test
    void formatSummary_preservesOrderAndOmitsBlank() {
        LinkedHashMap<String, String> fields = PortainerBuildLogger.summaryFields();
        fields.put("step", "stack");
        fields.put("outcome", "updated");
        fields.put("stackId", "42");
        fields.put("stackName", "myapp");
        fields.put("endpointId", "3");
        fields.put("type", "compose");
        fields.put("duration", "1.2s");
        fields.put("secret", null);
        fields.put("empty", "  ");
        assertEquals(
                "Summary step=stack outcome=updated stackId=42 stackName=myapp"
                        + " endpointId=3 type=compose duration=1.2s",
                PortainerBuildLogger.formatSummary(fields));
    }

    @Test
    void formatSummary_manifestAndHelmShapes() {
        Map<String, String> manifest = PortainerBuildLogger.summaryFields();
        manifest.put("outcome", "updated");
        manifest.put("stackId", "7");
        manifest.put("duration", "800ms");
        assertEquals(
                "Summary outcome=updated stackId=7 duration=800ms",
                PortainerBuildLogger.formatSummary(manifest));

        Map<String, String> helm = PortainerBuildLogger.summaryFields();
        helm.put("outcome", "created");
        helm.put("release", "nginx-123");
        helm.put("chart", "nginx");
        helm.put("version", "0.1.0");
        helm.put("duration", "1.6s");
        assertEquals(
                "Summary outcome=created release=nginx-123 chart=nginx version=0.1.0 duration=1.6s",
                PortainerBuildLogger.formatSummary(helm));
    }

    @Test
    void summaryWithDuration_appendsDurationField() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8)) {
            PortainerBuildLogger log = new PortainerBuildLogger(
                    Logger.getLogger("test"), listener, false);
            long started = System.nanoTime() - 5_000_000L;
            var fields = PortainerBuildLogger.summaryFields();
            fields.put("files", "1");
            log.summaryWithDuration(started, fields);
            String out = buf.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("[INFO] Summary files=1 duration="));
            assertTrue(fields.containsKey("duration"));
        }
    }

    @Test
    void formatSummary_neverIncludesSecretishTokensInFixture() {
        LinkedHashMap<String, String> fields = PortainerBuildLogger.summaryFields();
        fields.put("outcome", "created");
        fields.put("duration", "10ms");
        String line = PortainerBuildLogger.formatSummary(fields);
        assertTrue(line.startsWith("Summary "));
        assertFalse(line.toLowerCase().contains("token"));
        assertFalse(line.toLowerCase().contains("password"));
        assertFalse(line.toLowerCase().contains("secret_id"));
        assertFalse(line.toLowerCase().contains("role_id"));
        assertFalse(line.contains("apiKey"));
        assertFalse(line.contains("Authorization"));
    }

    @Test
    void repoHost_extractsHostOnly() {
        assertEquals("charts.example", PortainerBuildLogger.repoHost("https://charts.example/helm"));
        assertEquals("charts.example", PortainerBuildLogger.repoHost("http://charts.example:8080/path"));
        assertNull(PortainerBuildLogger.repoHost(null));
        assertNull(PortainerBuildLogger.repoHost(" "));
        assertNull(PortainerBuildLogger.repoHost("not-a-url"));
    }

    @Test
    void infoGoesToConsoleNotJulInfo() throws Exception {
        Logger jul = Logger.getLogger("PortainerBuildLoggerTest.infoJul");
        Level previous = jul.getLevel();
        jul.setLevel(Level.ALL);
        RecordingHandler handler = new RecordingHandler();
        jul.addHandler(handler);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
                PortainerBuildLogger log = new PortainerBuildLogger(jul, listener, false)) {
            log.open(PortainerBuildLogger.TITLE_STACK);
            log.info("stack name=demo");
            log.warn("soft prune");
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(previous);
        }
        String console = buf.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("[INFO] Stack name=demo"));
        assertTrue(console.contains("[WARN] Soft prune"));
        assertTrue(handler.records.stream().noneMatch(r -> r.getLevel() == Level.INFO));
        assertTrue(handler.records.stream().anyMatch(
                r -> r.getLevel() == Level.FINE && r.getMessage().contains("Stack name=demo")));
        assertTrue(handler.records.stream().anyMatch(
                r -> r.getLevel() == Level.WARNING && r.getMessage().contains("Soft prune")));
    }

    private static final class RecordingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        private boolean closed;

        @Override
        public void publish(LogRecord record) {
            if (closed || record == null) {
                return;
            }
            records.add(record);
        }

        @Override
        public void flush() {
            // In-memory list; publish already stored the record.
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
