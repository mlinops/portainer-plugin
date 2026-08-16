package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ConnectionTesterTest {

    @BeforeEach
    public void clearLoopbackAllow() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @AfterEach
    public void restoreLoopbackAllow() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @Test
    public void extractHost_supportsUnderscoreHostname() {
        assertEquals("port_ainer.example",
                ConnectionTester.extractHost("https://port_ainer.example:9443"));
    }

    @Test
    public void extractHost_supportsIpv6Brackets() {
        assertEquals("2001:db8::1",
                ConnectionTester.extractHost("https://[2001:db8::1]:9443/"));
    }

    @Test
    public void extractHost_rejectsNullAndBlank() {
        IllegalArgumentException nullEx = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost(null));
        assertTrue(nullEx.getMessage().toLowerCase().contains("missing"));

        IllegalArgumentException blankEx = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("   "));
        assertTrue(blankEx.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    public void extractHost_manualAuthority_stripsUserInfo() {
        // Underscore host → URI.getHost() is null → manual authority + userinfo strip.
        assertEquals("my_host.example",
                ConnectionTester.extractHost("https://user:pass@my_host.example/api"));
    }

    @Test
    public void extractHost_rejectsMissingScheme() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("portainer.example"));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    public void extractHost_rejectsIpv6AuthorityMissingClosingBracket() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("https://[::1"));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    public void extractHost_rejectsBlankHostAfterParse() {
        IllegalArgumentException emptyAuthority = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("https://"));
        assertTrue(emptyAuthority.getMessage().toLowerCase().contains("missing"));

        IllegalArgumentException slashOnly = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("http:///"));
        assertTrue(slashOnly.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    public void extractHost_rejectsWhitespaceAndBackslashInHost() {
        IllegalArgumentException space = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.extractHost("https://bad host.example"));
        assertTrue(space.getMessage().toLowerCase().contains("invalid"));

        IllegalArgumentException backslash = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.extractHost("https://host\\evil.example"));
        assertTrue(backslash.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    public void assertUriHostAllowed_rejectsLoopback() {
        try {
            ConnectionTester.assertUriHostAllowed(URI.create("http://127.0.0.1:1/api"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("not allowed"));
        }
    }

    @Test
    public void assertUriHostAllowed_rejectsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.assertUriHostAllowed(null));
        assertTrue(ex.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    public void assertUriHostAllowed_rejectsBadScheme() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertUriHostAllowed(URI.create("ftp://portainer.example/")));
        assertTrue(ex.getMessage().toLowerCase().contains("scheme"));
    }

    @Test
    public void assertUriHostAllowed_hostWithPort_stillBlocksLoopback() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertUriHostAllowed(URI.create("https://127.0.0.1:9443/")));
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void assertUriHostAllowed_ipv6Host_stillBlocksLoopback() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertUriHostAllowed(URI.create("http://[::1]:8443/")));
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void assertUriHostAllowed_blankHost_fallsBackToAssertHostAllowed() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertUriHostAllowed(URI.create("http:///")));
        assertTrue(ex.getMessage().toLowerCase().contains("missing")
                || ex.getMessage().toLowerCase().contains("invalid")
                || ex.getMessage().toLowerCase().contains("resolv")
                || ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void deferUnknownHost_doesNotThrowForUnresolvable() {
        ConnectionTester.assertHostAllowed(
                "https://no-such-host-portainer-test.invalid/",
                ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST);
    }

    @Test
    public void requireResolved_rejectsUnresolvable() {
        try {
            ConnectionTester.assertHostAllowed(
                    "https://no-such-host-portainer-test.invalid/",
                    ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("resolv"));
        }
    }

    @Test
    public void requireResolved_rejectsMetadata() {
        try {
            ConnectionTester.assertHostAllowed(
                    "http://169.254.169.254/",
                    ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("not allowed"));
        }
    }

    @Test
    public void assertHostAllowed_defaultDefer_blocksLocalhostAndLoopbackLiterals() {
        assertBlocked("http://localhost/");
        assertBlocked("http://foo.localhost/");
        assertBlocked("http://127.0.0.1/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://0.0.0.0/");
    }

    @Test
    public void assertHostAllowed_defaultDefer_blocksMetadataHostnames() {
        assertBlocked("http://metadata/");
        assertBlocked("http://metadata.google.internal/");
        assertBlocked("http://metadata.google/");
    }

    @Test
    public void assertHostAllowed_defaultDefer_unresolvableDoesNotThrow() {
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "https://no-such-host-portainer-test.invalid/"));
    }

    @Test
    public void allowLoopbackForTests_allows127ButStillBlocksMetadata() {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");

        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed("http://127.0.0.1/"));

        IllegalArgumentException metadata = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertHostAllowed("http://metadata.google.internal/"));
        assertTrue(metadata.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void isIpv4LoopbackLiteral_blocksValid127() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertHostAllowed("http://127.0.0.1/"));
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void isIpv4LoopbackLiteral_ignoresInvalidPartCountsAndValues() {
        // Not a 4-octet literal → hostname blocklist skip; DEFER tolerates failed/odd DNS.
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.0.0.256/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.a.0.1/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.1/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
    }

    private static void assertBlocked(String url) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.assertHostAllowed(url));
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"), ex.getMessage());
    }
}
