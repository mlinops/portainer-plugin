package io.jenkins.plugins.portainer;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@WithJenkins
public class VaultInheritTest {

    @Test
    public void descriptor_displayNameAndSummary(JenkinsRule jenkins) {
        VaultInherit.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(VaultInherit.DescriptorImpl.class);
        assertEquals("Inherit from System", d.getDisplayName());
        assertEquals(VaultPluginInherit.inheritSummary(), d.getVaultInheritSummary());
        assertEquals("Vault Plugin is not configured.", d.getVaultInheritSummary());
    }

    @Test
    public void kvDescriptor_doCheck_emptyOk_invalidError(JenkinsRule jenkins) throws Exception {
        VaultInherit.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(VaultInherit.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(FormValidation.Kind.OK, d.doCheckVaultPath("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckVaultMount("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckVaultVersion("", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckVaultPath("apps/demo", project).kind);

        assertEquals(FormValidation.Kind.ERROR, d.doCheckVaultPath("../x", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckVaultMount("a/b", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckVaultVersion("x", project).kind);
    }

    @Test
    public void doCheckVaultPath_withoutConfigure_denied(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy()
                        .grant(Jenkins.READ, Item.READ).everywhere().to("viewer"));

        VaultInherit.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(VaultInherit.DescriptorImpl.class);
        try (ACLContext ignored = ACL.as(User.getById("viewer", true))) {
            assertThrows(AccessDeniedException.class, () -> d.doCheckVaultPath("apps/demo", project));
        }
    }
}
