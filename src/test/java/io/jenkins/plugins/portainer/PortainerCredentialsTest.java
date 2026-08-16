package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerCredentialsTest {

    @Test
    public void resolveAppRole_usernamePassword(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-approle",
                        "AppRole",
                        "role-id-VALUE",
                        "secret-id-VALUE"));
        SystemCredentialsProvider.getInstance().save();

        PortainerCredentials.AppRoleIds ids =
                PortainerCredentials.resolveAppRole("vault-approle", null);
        assertEquals("role-id-VALUE", ids.roleId);
        assertEquals("secret-id-VALUE", ids.secretId);
    }

    @Test
    public void resolveAppRole_rejectsSecretText(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-secret-text",
                        "not approle",
                        Secret.fromString("only-one-secret")));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveAppRole("vault-secret-text", null));
        assertTrue(ex.getMessage().contains("not found") || ex.getMessage().contains("not Username/Password"));
        assertTrue(!ex.getMessage().contains("only-one-secret"));
    }

    @Test
    public void resolveAppRole_emptyUsername_failsWithoutLeaking(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-empty-user",
                        "AppRole",
                        "",
                        "secret-id-VALUE"));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveAppRole("vault-empty-user", null));
        assertTrue(ex.getMessage().contains("empty username"));
        assertTrue(!ex.getMessage().contains("secret-id-VALUE"));
    }

    @Test
    public void resolveAppRole_blankId(JenkinsRule jenkins) {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveAppRole("  ", null));
        assertTrue(ex.getMessage().contains("Vault AppRole credentials ID is required"));
    }

    @Test
    public void resolveAppRole_scopedToItem(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-scoped",
                        "AppRole",
                        "r1",
                        "s1"));
        SystemCredentialsProvider.getInstance().save();

        PortainerCredentials.AppRoleIds ids =
                PortainerCredentials.resolveAppRole("vault-scoped", project);
        assertEquals("r1", ids.roleId);
        assertEquals("s1", ids.secretId);
    }

    @Test
    public void resolveApiKey_secretText(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "portainer-token",
                        "API key",
                        Secret.fromString("ptr_TOKEN_VALUE")));
        SystemCredentialsProvider.getInstance().save();

        assertEquals("ptr_TOKEN_VALUE", PortainerCredentials.resolveApiKey("portainer-token", null));
    }

    @Test
    public void fillSecretText_listsWhenManage(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "api-listed",
                        "API key",
                        Secret.fromString("secret")));
        SystemCredentialsProvider.getInstance().save();

        ListBoxModel model = PortainerCredentials.fillSecretText(null, "api-listed");
        assertTrue(containsValue(model, "api-listed"));
        assertTrue(containsValue(model, ""));
    }

    @Test
    public void fillSecretText_withoutManage_returnsCurrentOnly(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "api-hidden",
                        "API key",
                        Secret.fromString("secret")));
        SystemCredentialsProvider.getInstance().save();

        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));

        try (ACLContext ignored = ACL.as(User.getById("reader", true))) {
            ListBoxModel model = PortainerCredentials.fillSecretText(null, "kept-current");
            assertTrue(containsValue(model, "kept-current"));
            assertFalse(containsValue(model, "api-hidden"));
        }
    }

    @Test
    public void fillSecretText_withoutConfigure_returnsCurrentOnly(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "api-job-hidden",
                        "API key",
                        Secret.fromString("secret")));
        SystemCredentialsProvider.getInstance().save();

        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy()
                        .grant(Jenkins.READ, Item.READ).everywhere().to("viewer")
                        .grant(Item.CONFIGURE).everywhere().to("configurer"));

        try (ACLContext ignored = ACL.as(User.getById("viewer", true))) {
            ListBoxModel model = PortainerCredentials.fillSecretText(project, "job-current");
            assertTrue(containsValue(model, "job-current"));
            assertFalse(containsValue(model, "api-job-hidden"));
        }
    }

    @Test
    public void fillVaultAppRole_listsUsernamePasswordOnly(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "approle-ok",
                        "AppRole",
                        "role",
                        "secret"));
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "secret-text-skip",
                        "not approle",
                        Secret.fromString("x")));
        SystemCredentialsProvider.getInstance().save();

        ListBoxModel model = PortainerCredentials.fillVaultAppRoleCredentials(null, null);
        assertTrue(containsValue(model, "approle-ok"));
        assertFalse(containsValue(model, "secret-text-skip"));
    }

    @Test
    public void resolveAppRole_emptyPassword_failsWithoutLeaking(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "vault-empty-pass",
                        "AppRole",
                        "role-id-VALUE",
                        ""));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveAppRole("vault-empty-pass", null));
        assertTrue(ex.getMessage().contains("empty password"));
        assertTrue(!ex.getMessage().contains("role-id-VALUE"));
    }

    @Test
    public void resolveApiKey_blankId(JenkinsRule jenkins) {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveApiKey(" ", null));
        assertTrue(ex.getMessage().contains("Credentials ID is required"));
    }

    @Test
    public void resolveSecretText_emptySecret(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "empty-secret",
                        "API key",
                        Secret.fromString("")));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveSecretText("empty-secret", null, "Portainer API key"));
        assertTrue(ex.getMessage().contains("empty secret"));
    }

    @Test
    public void resolveSecretText_rejectsUsernamePassword(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "not-secret-text",
                        "up",
                        "u",
                        "p"));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveSecretText("not-secret-text", null, "x"));
        assertTrue(ex.getMessage().contains("not found") || ex.getMessage().contains("expected type"));
    }

    @Test
    public void resolveGitAuth_blankReturnsNull(JenkinsRule jenkins) {
        assertNull(PortainerCredentials.resolveGitAuth(null, null));
        assertNull(PortainerCredentials.resolveGitAuth("  ", null));
    }

    @Test
    public void resolveGitAuth_usernamePassword(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-up",
                        "git",
                        "clone-user",
                        "clone-pass"));
        SystemCredentialsProvider.getInstance().save();

        PortainerCredentials.GitAuth auth = PortainerCredentials.resolveGitAuth("git-up", null);
        assertEquals("clone-user", auth.username);
        assertEquals("clone-pass", auth.password);
    }

    @Test
    public void resolveGitAuth_secretTextAsOauth2(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-pat",
                        "PAT",
                        Secret.fromString("glpat-TOKEN")));
        SystemCredentialsProvider.getInstance().save();

        PortainerCredentials.GitAuth auth = PortainerCredentials.resolveGitAuth("git-pat", null);
        assertEquals("oauth2", auth.username);
        assertEquals("glpat-TOKEN", auth.password);
    }

    @Test
    public void resolveGitAuth_emptyPassword(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-empty-pass",
                        "git",
                        "u",
                        ""));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveGitAuth("git-empty-pass", null));
        assertTrue(ex.getMessage().contains("empty password"));
    }

    @Test
    public void resolveGitAuth_emptySecretText(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-empty-secret",
                        "PAT",
                        Secret.fromString("")));
        SystemCredentialsProvider.getInstance().save();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveGitAuth("git-empty-secret", null));
        assertTrue(ex.getMessage().contains("empty secret"));
    }

    @Test
    public void resolveGitAuth_missingCredential(JenkinsRule jenkins) {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PortainerCredentials.resolveGitAuth("no-such-git-cred", null));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    public void fillSecretOrUsernamePassword_listsBoth(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-up-listed",
                        "git",
                        "u",
                        "p"));
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-pat-listed",
                        "PAT",
                        Secret.fromString("tok")));
        SystemCredentialsProvider.getInstance().save();

        ListBoxModel model = PortainerCredentials.fillSecretOrUsernamePassword(null, null);
        assertTrue(containsValue(model, "git-up-listed"));
        assertTrue(containsValue(model, "git-pat-listed"));
    }

    @Test
    public void fillSecretText_withConfigure_listsCredentials(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "api-job-visible",
                        "API key",
                        Secret.fromString("secret")));
        SystemCredentialsProvider.getInstance().save();

        ListBoxModel model = PortainerCredentials.fillSecretText(project, "api-job-visible");
        assertTrue(containsValue(model, "api-job-visible"));
        assertTrue(containsValue(model, ""));
    }

    private static boolean containsValue(ListBoxModel model, String value) {
        for (ListBoxModel.Option option : model) {
            if (value == null ? option.value == null : value.equals(option.value)) {
                return true;
            }
        }
        return false;
    }
}
