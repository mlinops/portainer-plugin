package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI smoke: load Freestyle job from golden {@code config.xml}, open Configure, assert Stapler {@code _.field} inputs.
 * <p>
 * Golden XML: {@code src/test/resources/ui/stack-freestyle-config.xml} (classpath {@code /ui/stack-freestyle-config.xml}).
 * Smoke only — no validateButton click, no build.
 */
@WithJenkins
public class PortainerStackUiSmokeTest {

    private static final String PORTAINER_API_CRED_ID = "portainer-api-example";
    private static final String GIT_CRED_ID = "git-token-example";
    private static final String GOLDEN_CONFIG = "/ui/stack-freestyle-config.xml";

    @Test
    public void freestyle_configurePage_showsStackFieldsFromGoldenXml(JenkinsRule jenkins) throws Exception {
        seedCredentials();
        seedGlobalPortainer();

        FreeStyleProject project = jenkins.createFreeStyleProject("portainer-stack-ui-smoke");
        try (InputStream in = PortainerStackUiSmokeTest.class.getResourceAsStream(GOLDEN_CONFIG)) {
            assertNotNull(in, "Missing classpath resource " + GOLDEN_CONFIG);
            project.updateByXml(new StreamSource(in));
        }
        project = jenkins.jenkins.getItemByFullName(project.getFullName(), FreeStyleProject.class);
        assertNotNull(project);
        assertNotNull(project.getBuildersList().get(PortainerStackBuilder.class));

        try (JenkinsRule.WebClient wc = jenkins.createWebClient()) {
            HtmlPage page = wc.getPage(project, "configure");
            HtmlForm form = page.getFormByName("config");

            assertEquals("1", form.getInputByName("_.endpointId").getValue());
            assertEquals("ui-smoke-stack", form.getInputByName("_.stackName").getValue());
            assertEquals(
                    "https://gitlab.example/group/stack.git",
                    form.getInputByName("_.repositoryUrl").getValue());
            assertEquals(
                    "https://portainer.example",
                    form.getInputByName("_.portainerUrl").getValue());
            assertEquals(
                    "docker-compose.yml",
                    form.getInputByName("_.composeFilePath").getValue());

            assertEquals(
                    "compose",
                    form.getSelectByName("_.stackType").getSelectedOptions().get(0).getValueAttribute());

            assertTrue(
                    form.getInputByName("_.validateOnly").isChecked(),
                    "validateOnly should be checked from golden XML");
        }
    }

    private static void seedCredentials() throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        PORTAINER_API_CRED_ID,
                        "UiSmoke Portainer API key",
                        Secret.fromString("portainer-api-token-example")));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        GIT_CRED_ID,
                        "UiSmoke Git token",
                        "git",
                        "git-token-value-example"));
        SystemCredentialsProvider.getInstance().save();
    }

    private static void seedGlobalPortainer() {
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("ui-smoke");
        cfg.setPortainerUrl("https://portainer.example");
        cfg.setCredentialsId(PORTAINER_API_CRED_ID);
        cfg.save();
    }
}
