package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComposeYamlValidatorTest {

    @Test
    public void acceptsMinimalCompose() {
        assertNull(ComposeYamlValidator.validate(
                "services:\n  web:\n    image: nginx:alpine\n"));
    }

    @Test
    public void rejectsEmpty() {
        String err = ComposeYamlValidator.validate("   ");
        assertTrue(err != null && err.toLowerCase().contains("empty"));
    }

    @Test
    public void rejectsInvalidSyntax() {
        String err = ComposeYamlValidator.validate("services: [\n  - broken");
        assertTrue(err != null && err.startsWith("Invalid Compose YAML"));
    }

    @Test
    public void rejectsMissingServices() {
        String err = ComposeYamlValidator.validate("version: '3.8'\nnetworks: {}\n");
        assertTrue(err != null && err.contains("services"));
    }

    @Test
    public void rejectsEmptyServices() {
        String err = ComposeYamlValidator.validate("services: {}\n");
        assertTrue(err != null && err.toLowerCase().contains("non-empty"));
    }

    @Test
    public void acceptsInlineMultilineCompose() {
        String yaml = """
                services:
                  web:
                    image: nginx:alpine
                    ports:
                      - "80:80"
                """;
        assertNull(ComposeYamlValidator.validate(yaml));
    }
}
