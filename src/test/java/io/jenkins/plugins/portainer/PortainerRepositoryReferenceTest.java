package io.jenkins.plugins.portainer;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PortainerRepositoryReferenceTest {

    @Test
    public void blank_usesDefaultMain() {
        assertEquals(
                PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE,
                PortainerStackBuilder.resolveRepositoryReference(null, new EnvVars()));
        assertEquals(
                PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE,
                PortainerStackBuilder.resolveRepositoryReference("  ", null));
    }

    @Test
    public void expandsEnvPlaceholders() {
        EnvVars env = new EnvVars();
        env.put("BRANCH_NAME", "feature/x");
        env.put("GIT_BRANCH", "develop");
        assertEquals(
                "refs/heads/feature/x",
                PortainerStackBuilder.resolveRepositoryReference("refs/heads/${BRANCH_NAME}", env));
        assertEquals(
                "refs/heads/develop",
                PortainerStackBuilder.resolveRepositoryReference("refs/heads/$GIT_BRANCH", env));
    }

    @Test
    public void expandToBlank_fallsBackToDefault() {
        EnvVars env = new EnvVars();
        env.put("EMPTY", "");
        assertEquals(
                PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE,
                PortainerStackBuilder.resolveRepositoryReference("${EMPTY}", env));
    }
}
