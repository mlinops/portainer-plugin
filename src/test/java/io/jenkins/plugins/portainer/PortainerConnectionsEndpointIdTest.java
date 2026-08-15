package io.jenkins.plugins.portainer;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerConnectionsEndpointIdTest {

    @Test
    public void checkEndpointId_allowsMacro(JenkinsRule jenkins) {
        configureSystemPortainer();
        assertEquals(
                FormValidation.Kind.OK,
                PortainerConnections.checkEndpointId("${ENDPOINT_ID}", "inherit").kind);
        assertEquals(
                FormValidation.Kind.OK,
                PortainerConnections.checkEndpointId("$ENDPOINT_ID", "inherit").kind);
    }

    @Test
    public void checkEndpointId_rejectsNonNumericWithoutMacro(JenkinsRule jenkins) {
        configureSystemPortainer();
        assertEquals(
                FormValidation.Kind.ERROR,
                PortainerConnections.checkEndpointId("abc", "inherit").kind);
    }

    @Test
    public void resolveEndpointId_expandsBuildEnv(JenkinsRule jenkins) throws Exception {
        EnvVars env = new EnvVars();
        env.put("ENDPOINT_ID", "326");
        assertEquals(326, PortainerConnections.resolveEndpointId("${ENDPOINT_ID}", env));
    }

    @Test
    public void resolveEndpointId_rejectsUnresolvedMacro(JenkinsRule jenkins) {
        EnvVars env = new EnvVars();
        AbortException ex = assertThrows(
                AbortException.class,
                () -> PortainerConnections.resolveEndpointId("${MISSING_ENDPOINT}", env));
        assertTrue(ex.getMessage().toLowerCase().contains("positive integer"));
    }

    private static void configureSystemPortainer() {
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setPortainerUrl("https://portainer.example:9443");
        cfg.setCredentialsId("portainer-api-key");
        cfg.save();
    }
}
