package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwarmNamedResourceTest {

    @Test
    void desired_defensiveCopyOfContent() {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        SwarmNamedResource.Desired desired =
                new SwarmNamedResource.Desired("app", "app-aaaa", "aaaa", content);
        content[0] = 'X';
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), desired.content);
    }

    @Test
    void ensure_createsMissingAndSkipsExisting() throws Exception {
        PortainerBuildLogger log = quietLog();
        List<String> created = new ArrayList<>();
        List<PortainerClient.DockerConfigSummary> listed = List.of(
                new PortainerClient.DockerConfigSummary(
                        "id-keep", "app-aaaa", Map.of(SwarmNamedResource.Kind.SECRET.labelBase, "app")));

        SwarmNamedResource.Outcome outcome = SwarmNamedResource.ensure(
                SwarmNamedResource.Kind.SECRET,
                () -> listed,
                (name, data, labels) -> created.add(name),
                List.of(
                        new SwarmNamedResource.Desired("app", "app-aaaa", "aaaa", "x".getBytes(StandardCharsets.UTF_8)),
                        new SwarmNamedResource.Desired("db", "db-bbbb", "bbbb", "y".getBytes(StandardCharsets.UTF_8))),
                null,
                log);

        assertEquals(1, outcome.created);
        assertEquals(1, outcome.skipped);
        assertEquals(List.of("db-bbbb"), created);
        assertEquals(2, outcome.ensured.size());
    }

    @Test
    void ensure_abortsWhenCreateFails() {
        PortainerBuildLogger log = quietLog();
        AbortException ex = assertThrows(AbortException.class, () -> SwarmNamedResource.ensure(
                SwarmNamedResource.Kind.CONFIG,
                List::of,
                (name, data, labels) -> {
                    throw new IOException("create failed");
                },
                List.of(new SwarmNamedResource.Desired("cfg", "cfg-hash", "hash", new byte[] {1})),
                null,
                log));
        assertTrue(ex.getMessage().contains("Failed to create Docker config"));
    }

    @Test
    void pruneStaleByBaseLabel_removesOldAndSkipsInUse() {
        PortainerBuildLogger log = quietLog();
        AtomicInteger removed = new AtomicInteger();
        List<String> removedIds = new ArrayList<>();
        List<PortainerClient.DockerConfigSummary> listed = List.of(
                new PortainerClient.DockerConfigSummary(
                        "keep-id",
                        "app-new",
                        Map.of(SwarmNamedResource.Kind.SECRET.labelBase, "app")),
                new PortainerClient.DockerConfigSummary(
                        "stale-id",
                        "app-old",
                        Map.of(SwarmNamedResource.Kind.SECRET.labelBase, "app")),
                new PortainerClient.DockerConfigSummary(
                        "busy-id",
                        "app-busy",
                        Map.of(SwarmNamedResource.Kind.SECRET.labelBase, "app")),
                new PortainerClient.DockerConfigSummary("other-id", "unrelated", Map.of()));

        SwarmNamedResource.pruneStaleByBaseLabel(
                SwarmNamedResource.Kind.SECRET,
                id -> {
                    if ("busy-id".equals(id)) {
                        throw new IOException("400 secret is in use");
                    }
                    removed.incrementAndGet();
                    removedIds.add(id);
                },
                listed,
                List.of(new SwarmNamedResource.Ensured("app", "app-new", "new")),
                log);

        assertEquals(1, removed.get());
        assertEquals(List.of("stale-id"), removedIds);
    }

    private static PortainerBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new PortainerBuildLogger(Logger.getLogger("SwarmNamedResourceTest"), listener, false);
    }
}
