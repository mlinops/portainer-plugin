package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PortainerStackNameTest {

    @Test
    public void acceptsPortainerPattern() {
        assertNull(PortainerStackName.validate("my-name"));
        assertNull(PortainerStackName.validate("abc_123"));
        assertNull(PortainerStackName.validate("stack"));
        assertNull(PortainerStackName.validate("_leading"));
        assertNull(PortainerStackName.validate("-leading"));
    }

    @Test
    public void rejectsInvalidNames() {
        assertNotNull(PortainerStackName.validate(""));
        assertNotNull(PortainerStackName.validate("MyApp"));
        assertNotNull(PortainerStackName.validate("my app"));
        assertNotNull(PortainerStackName.validate("my.app"));
        assertTrue(PortainerStackName.validate("Bad").contains("lowercase"));
        assertThrows(IllegalArgumentException.class, () -> PortainerStackName.requireValid("UPPER"));
    }

    @Test
    public void optionalAllowsBlankButRejectsBadFormat() {
        assertNull(PortainerStackName.validateOptional(null));
        assertNull(PortainerStackName.validateOptional(""));
        assertNull(PortainerStackName.validateOptional("   "));
        assertNull(PortainerStackName.validateOptional("my-app"));
        assertNotNull(PortainerStackName.validateOptional("MyApp"));
        PortainerStackName.requireValidOptional("");
        assertThrows(IllegalArgumentException.class, () -> PortainerStackName.requireValidOptional("Bad"));
    }
}
