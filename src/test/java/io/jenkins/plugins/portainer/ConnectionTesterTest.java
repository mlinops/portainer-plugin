package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTesterTest {

    @BeforeEach
    void clearLoopbackAllow() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @AfterEach
    void restoreLoopbackAllow() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @Test
    void extractHost_supportsUnderscoreHostname() {
        assertEquals("port_ainer.example",
                ConnectionTester.extractHost("https://port_ainer.example:9443"));
    }

    @Test
    void extractHost_supportsIpv6Brackets() {
        assertEquals("2001:db8::1",
                ConnectionTester.extractHost("https://[2001:db8::1]:9443/"));
    }

    @Test
    void extractHost_rejectsNullAndBlank() {
        IllegalArgumentException nullEx = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost(null));
        assertTrue(nullEx.getMessage().toLowerCase().contains("missing"));

        IllegalArgumentException blankEx = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("   "));
        assertTrue(blankEx.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    void extractHost_manualAuthority_stripsUserInfo() {
        // Underscore host → URI.getHost() is null → manual authority + userinfo strip.
        assertEquals("my_host.example",
                ConnectionTester.extractHost("https://user:pass@my_host.example/api"));
    }

    @Test
    void extractHost_rejectsMissingScheme() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("portainer.example"));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    void extractHost_rejectsIpv6AuthorityMissingClosingBracket() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("https://[::1"));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    void extractHost_rejectsBlankHostAfterParse() {
        IllegalArgumentException emptyAuthority = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("https://"));
        assertTrue(emptyAuthority.getMessage().toLowerCase().contains("missing"));

        IllegalArgumentException slashOnly = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("http:///"));
        assertTrue(slashOnly.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    void extractHost_rejectsWhitespaceAndBackslashInHost() {
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
    void assertUriHostAllowed_rejectsLoopback() {
        IllegalArgumentException ex = assertUriRejected("http://127.0.0.1:1/api");
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    void assertUriHostAllowed_rejectsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.assertUriHostAllowed(null));
        assertTrue(ex.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    void assertUriHostAllowed_rejectsBadScheme() {
        IllegalArgumentException ex = assertUriRejected("ftp://portainer.example/");
        assertTrue(ex.getMessage().toLowerCase().contains("scheme"));
    }

    @Test
    void assertUriHostAllowed_hostWithPort_stillBlocksLoopback() {
        IllegalArgumentException ex = assertUriRejected("https://127.0.0.1:9443/");
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    void assertUriHostAllowed_ipv6Host_stillBlocksLoopback() {
        IllegalArgumentException ex = assertUriRejected("http://[::1]:8443/");
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    void assertUriHostAllowed_blankHost_fallsBackToAssertHostAllowed() {
        IllegalArgumentException ex = assertUriRejected("http:///");
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("missing")
                || msg.contains("invalid")
                || msg.contains("resolv")
                || msg.contains("not allowed"));
    }

    @Test
    void deferUnknownHost_doesNotThrowForUnresolvable() {
        ConnectionTester.assertHostAllowed(
                "https://no-such-host-portainer-test.invalid/",
                ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST);
    }

    @Test
    void requireResolved_rejectsUnresolvable() {
        assertBlocked(
                "https://no-such-host-portainer-test.invalid/",
                ConnectionTester.DnsPolicy.REQUIRE_RESOLVED,
                "resolv");
    }

    @Test
    void requireResolved_rejectsMetadata() {
        assertBlocked(
                "http://169.254.169.254/",
                ConnectionTester.DnsPolicy.REQUIRE_RESOLVED,
                "not allowed");
    }

    @Test
    void assertHostAllowed_defaultDefer_blocksLocalhostAndLoopbackLiterals() {
        assertBlocked("http://localhost/");
        assertBlocked("http://foo.localhost/");
        assertBlocked("http://127.0.0.1/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://0.0.0.0/");
    }

    @Test
    void assertHostAllowed_defaultDefer_blocksMetadataHostnames() {
        assertBlocked("http://metadata/");
        assertBlocked("http://metadata.google.internal/");
        assertBlocked("http://metadata.google/");
    }

    @Test
    void assertHostAllowed_defaultDefer_unresolvableDoesNotThrow() {
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "https://no-such-host-portainer-test.invalid/"));
    }

    @Test
    void allowLoopbackForTests_allows127ButStillBlocksMetadata() {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");

        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed("http://127.0.0.1/"));
        assertBlocked("http://metadata.google.internal/");
    }

    @Test
    void isIpv4LoopbackLiteral_blocksValid127() {
        assertBlocked("http://127.0.0.1/");
    }

    @Test
    void isIpv4LoopbackLiteral_ignoresInvalidPartCountsAndValues() {
        // Not a 4-octet literal → hostname blocklist skip; DEFER tolerates failed/odd DNS.
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.0.0.256/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.a.0.1/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.1/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
    }

    private static IllegalArgumentException assertUriRejected(String spec) {
        URI uri = URI.create(spec);
        return assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertUriHostAllowed(uri));
    }

    private static void assertBlocked(String url) {
        assertBlocked(url, ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST, "not allowed");
    }

    private static void assertBlocked(String url, ConnectionTester.DnsPolicy policy, String needle) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertHostAllowed(url, policy));
        assertTrue(ex.getMessage().toLowerCase().contains(needle), ex.getMessage());
    }
}
