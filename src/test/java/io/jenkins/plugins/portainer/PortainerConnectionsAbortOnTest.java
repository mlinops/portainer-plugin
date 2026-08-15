package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortainerConnectionsAbortOnTest {

    @Test
    void abortOn_returnsSupplierValue() throws Exception {
        assertEquals(42, PortainerConnections.abortOn(quietLog(), () -> 42));
    }

    @Test
    void abortOn_wrapsIllegalArgument() {
        AbortException ex = assertThrows(
                AbortException.class,
                () -> PortainerConnections.abortOn(quietLog(), () -> {
                    throw new IllegalArgumentException("bad input");
                }));
        assertTrue(ex.getMessage().contains("bad input"));
    }

    private static PortainerBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new PortainerBuildLogger(Logger.getLogger("AbortOnTest"), listener, false);
    }
}
