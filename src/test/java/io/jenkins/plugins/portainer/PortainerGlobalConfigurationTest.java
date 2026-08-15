package io.jenkins.plugins.portainer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.GlobalConfigurationCategory;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerGlobalConfigurationTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/status", exchange -> respond(exchange, 200, "{\"Version\":\"2.39.3\"}"));
        server.createContext("/api/endpoints", exchange -> respond(exchange, 200, "[{\"Id\":1,\"Name\":\"local\"}]"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void roundTrip_andProbeConnection(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "portainer-api-key",
                        "Portainer token",
                        Secret.fromString("secret-token-value")));
        SystemCredentialsProvider.getInstance().save();

        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("prod");
        cfg.setPortainerUrl(base);
        cfg.setCredentialsId("portainer-api-key");
        cfg.setConnectTimeoutMs(2000);
        cfg.setReadTimeoutMs(2000);
        cfg.save();

        PortainerGlobalConfiguration loaded = PortainerGlobalConfiguration.get();
        assertEquals("prod", loaded.getName());
        assertEquals(base, loaded.getPortainerUrl());
        assertEquals("portainer-api-key", loaded.getCredentialsId());
        assertTrue(loaded.isConfigured());

        FormValidation ok = loaded.probeConnection(base, "portainer-api-key", 2000, 2000);
        assertEquals(FormValidation.Kind.OK, ok.kind);
        assertTrue(ok.getMessage().contains("Connection successful (Portainer v2.39.3)"));
        assertFalse(ok.getMessage().contains("environment"));
        assertFalse(ok.getMessage().contains("secret-token-value"));
        assertFalse(ok.getMessage().contains("status OK"));

        FormValidation badUrl = loaded.doCheckPortainerUrl("ftp://portainer.example");
        assertEquals(FormValidation.Kind.ERROR, badUrl.kind);

        FormValidation userInfo = loaded.doCheckPortainerUrl("https://u:p@portainer.example");
        assertEquals(FormValidation.Kind.ERROR, userInfo.kind);
        assertTrue(userInfo.getMessage().toLowerCase().contains("userinfo"));

        // Syntax-only: unresolved / exotic host must not fail form check (no DNS).
        FormValidation syntaxOnly = loaded.doCheckPortainerUrl(
                "https://no-such-host-portainer-port9.invalid:9443");
        assertEquals(FormValidation.Kind.OK, syntaxOnly.kind);

        FormValidation unauthorized = loaded.probeConnection(base, "", 2000, 2000);
        assertEquals(FormValidation.Kind.ERROR, unauthorized.kind);
    }

    @Test
    public void probeConnection_maps401WithoutLeakingSecret(JenkinsRule jenkins) throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/status", exchange -> respond(exchange, 401, "{\"message\":\"Unauthorized\"}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "bad-key",
                        "bad",
                        Secret.fromString("leaked-if-present")));
        SystemCredentialsProvider.getInstance().save();

        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        FormValidation result = cfg.probeConnection(base, "bad-key", 2000, 2000);
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertTrue(result.getMessage().startsWith("Connection failed — "));
        assertTrue(result.getMessage().contains("HTTP 401"));
        assertFalse(result.getMessage().contains("leaked-if-present"));
    }

    @Test
    public void category_isUnclassifiedForSystemPage(JenkinsRule jenkins) {
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        assertInstanceOf(GlobalConfigurationCategory.Unclassified.class, cfg.getCategory());
        assertEquals("Portainer", cfg.getDisplayName());
    }

    @Test
    public void configure_persistsWithoutProbe(JenkinsRule jenkins) throws Exception {
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("saved-without-probe");
        cfg.setPortainerUrl("https://portainer.example:9443");
        cfg.setCredentialsId("some-cred");
        cfg.save();

        PortainerGlobalConfiguration loaded = PortainerGlobalConfiguration.get();
        assertEquals("saved-without-probe", loaded.getName());
        assertEquals("https://portainer.example:9443", loaded.getPortainerUrl());
        assertEquals("some-cred", loaded.getCredentialsId());
        assertTrue(loaded.isConfigured());
    }

    @Test
    public void setters_persistWithoutExplicitSave(JenkinsRule jenkins) throws Exception {
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("script-console");
        cfg.setPortainerUrl("https://portainer.example:9443");
        cfg.setCredentialsId("script-cred");
        cfg.setConnectTimeoutMs(5000);
        cfg.setReadTimeoutMs(15000);
        // no cfg.save() — setters must persist

        PortainerGlobalConfiguration reloaded = new PortainerGlobalConfiguration();
        assertEquals("script-console", reloaded.getName());
        assertEquals("https://portainer.example:9443", reloaded.getPortainerUrl());
        assertEquals("script-cred", reloaded.getCredentialsId());
        assertEquals(5000, reloaded.getConnectTimeoutMs());
        assertEquals(15000, reloaded.getReadTimeoutMs());
        assertTrue(reloaded.isConfigured());
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
