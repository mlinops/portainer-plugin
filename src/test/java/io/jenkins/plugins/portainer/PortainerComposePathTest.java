package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PortainerComposePathTest {

    @Test
    public void acceptsRelativeYamlPaths() {
        assertNull(PortainerComposePath.validate("docker-compose.yml"));
        assertNull(PortainerComposePath.validate("deploy/stack.yaml"));
        assertEquals("compose/app.yml", PortainerComposePath.normalize("compose\\app.yml"));
    }

    @Test
    public void rejectsEmptyAbsoluteAndTraversal() {
        assertNotNull(PortainerComposePath.validate(""));
        assertNotNull(PortainerComposePath.validate("../x.yml"));
        assertNotNull(PortainerComposePath.validate("/abs/x.yml"));
        assertNotNull(PortainerComposePath.validate("noext"));
        assertThrows(IllegalArgumentException.class, () -> PortainerComposePath.normalize("..\\evil.yml"));
    }
}
