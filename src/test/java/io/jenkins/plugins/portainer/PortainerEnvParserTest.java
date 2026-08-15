package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PortainerEnvParserTest {

    @Test
    public void parse_keyValueAndComments() {
        List<PortainerClient.EnvPair> env = PortainerEnvParser.parse(
                "# comment\nIMAGE_TAG=1.2\n\nFEATURE=true\n");
        assertEquals(2, env.size());
        assertEquals("IMAGE_TAG", env.get(0).name);
        assertEquals("1.2", env.get(0).value);
        assertEquals("FEATURE", env.get(1).name);
    }

    @Test
    public void parse_bareKey_isShorthandForSelfReference() {
        List<PortainerClient.EnvPair> env = PortainerEnvParser.parse(
                "RABBITMQ_ERLANG_COOKIE\nRABBITMQ_DEFAULT_USER\n");
        assertEquals(2, env.size());
        assertEquals("RABBITMQ_ERLANG_COOKIE", env.get(0).name);
        assertEquals("${RABBITMQ_ERLANG_COOKIE}", env.get(0).value);
        assertEquals("RABBITMQ_DEFAULT_USER", env.get(1).name);
        assertEquals("${RABBITMQ_DEFAULT_USER}", env.get(1).value);
    }

    @Test
    public void parse_mixedBareKeyAndExplicit() {
        List<PortainerClient.EnvPair> env = PortainerEnvParser.parse(
                "FROM_BUILD\nLITERAL=fixed\n");
        assertEquals(2, env.size());
        assertEquals("${FROM_BUILD}", env.get(0).value);
        assertEquals("fixed", env.get(1).value);
    }

    @Test
    public void parse_rejectsInvalidBareKey() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PortainerEnvParser.parse("bad-key"));
        assertTrue(ex.getMessage().toLowerCase().contains("env key"));
    }

    @Test
    public void parse_rejectsEmptyKey() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PortainerEnvParser.parse("=value"));
        assertTrue(ex.getMessage().toLowerCase().contains("key"));
    }

    @Test
    public void parse_rejectsInvalidKey() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PortainerEnvParser.parse("bad-key=1"));
        assertTrue(ex.getMessage().toLowerCase().contains("env key"));
    }

    @Test
    public void gitRepositoryUrl_rejectsUserinfo() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryUrl.normalize("https://u:p@gitlab.example/group/stack.git"));
        assertTrue(ex.getMessage().toLowerCase().contains("userinfo"));
    }
}
