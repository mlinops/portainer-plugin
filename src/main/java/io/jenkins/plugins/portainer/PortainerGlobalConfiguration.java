package io.jenkins.plugins.portainer;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;

/**
 * Global Portainer connection settings (Manage Jenkins → System → Portainer).
 */
@Extension
@Symbol("portainerApi")
public class PortainerGlobalConfiguration extends GlobalConfiguration {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private String name = "default";
    private String portainerUrl;
    private String credentialsId;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

    public PortainerGlobalConfiguration() {
        load();
    }

    public static PortainerGlobalConfiguration get() {
        return GlobalConfiguration.all().get(PortainerGlobalConfiguration.class);
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Portainer";
    }

    public String getName() {
        return name;
    }

    @DataBoundSetter
    public void setName(String name) {
        String n = name == null ? "" : name.trim();
        this.name = n.isEmpty() ? "default" : n;
        save();
    }

    public String getPortainerUrl() {
        return portainerUrl;
    }

    @DataBoundSetter
    public void setPortainerUrl(String portainerUrl) {
        this.portainerUrl = portainerUrl == null ? null : portainerUrl.trim();
        save();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId == null ? null : credentialsId.trim();
        save();
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    @DataBoundSetter
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = clampTimeout(connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
        save();
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    @DataBoundSetter
    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = clampTimeout(readTimeoutMs, DEFAULT_READ_TIMEOUT_MS);
        save();
    }

    /**
     * Whether URL and API key credentials are set (build/step readiness).
     * Save does not call Portainer; runtime preflight probes during builds.
     */
    public boolean isConfigured() {
        return portainerUrl != null
                && !portainerUrl.isBlank()
                && credentialsId != null
                && !credentialsId.isBlank();
    }

    private static int clampTimeout(int value, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, 120_000);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws Descriptor.FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    @POST
    public FormValidation doCheckPortainerUrl(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        if (value == null || value.isBlank()) {
            return FormValidation.error("Portainer URL is required (https://portainer.example:9443).");
        }
        try {
            // Syntax only — no DNS / SSRF allowlist (those run on runtime preflight).
            PortainerUrl.normalizeBaseUrlSyntaxOnly(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    @POST
    public FormValidation doCheckConnectTimeoutMs(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        return validatePositiveInt(value, "Connect timeout (ms)", 100, 120_000, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    @POST
    public FormValidation doCheckReadTimeoutMs(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        return validatePositiveInt(value, "Read timeout (ms)", 100, 120_000, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Runtime / unit-test probe against Portainer (not exposed as a System UI Test connection button).
     */
    FormValidation probeConnection(
            String portainerUrl, String credentialsId, int connectMs, int readMs) {
        if (portainerUrl == null || portainerUrl.isBlank()) {
            return FormValidation.error("Set Portainer URL before testing the connection.");
        }
        final String base;
        try {
            base = PortainerUrl.normalizeBaseUrl(portainerUrl);
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
        if (credentialsId == null || credentialsId.isBlank()) {
            return FormValidation.error("Select Secret text credentials with the Portainer Access token.");
        }
        try {
            String apiKey = PortainerCredentials.resolveApiKey(credentialsId, null);
            try (PortainerClient client = new PortainerClient(connectMs, readMs)) {
                PortainerClient.ProbeDetails details = client.probeAccess(base, apiKey);
                String primary = "Connection successful (" + details.primaryLabel() + ")";
                if (primary.length() > 200) {
                    primary = primary.substring(0, 200) + "…";
                }
                return FormValidation.ok(primary);
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            // Keep connectivity / permission detail readable (port hints included).
            if (msg.length() > 280) {
                msg = msg.substring(0, 280) + "…";
            }
            if (!msg.endsWith(".") && !msg.endsWith("…")) {
                msg = msg + ".";
            }
            return FormValidation.error("Connection failed — " + msg);
        }
    }

    @POST
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        return PortainerCredentials.fillSecretText(null, credentialsId);
    }

    private static FormValidation validatePositiveInt(
            String raw, String label, int min, int max, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return FormValidation.ok("Using default " + defaultValue + ".");
        }
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return FormValidation.error(label + " must be an integer, e.g. " + defaultValue + ".");
        }
        if (value < min || value > max) {
            return FormValidation.error(
                    label + " must be between " + min + " and " + max + " (got " + value + ").");
        }
        return FormValidation.ok();
    }
}
