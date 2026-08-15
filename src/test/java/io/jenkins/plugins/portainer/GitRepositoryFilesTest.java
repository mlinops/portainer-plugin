package io.jenkins.plugins.portainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GitRepositoryFilesTest {

    @AfterEach
    public void clearOverrides() {
        GitRepositoryFiles.testOverride = null;
        GitRepositoryFiles.listTestOverride = null;
    }

    @Test
    public void shortRefForClone_stripsRefsPrefix() {
        assertEquals("main", GitRepositoryFiles.shortRefForClone("refs/heads/main"));
        assertEquals("v1.2.3", GitRepositoryFiles.shortRefForClone("refs/tags/v1.2.3"));
        assertEquals("feature/x", GitRepositoryFiles.shortRefForClone("feature/x"));
        assertEquals("main", GitRepositoryFiles.shortRefForClone(" "));
    }

    @Test
    public void cloneCommandLine_usesRepoUrlWithoutUserinfo() {
        String repo = "https://gitlab.example/group/values.git";
        String password = "s3cret:token";
        List<String> cmd = GitRepositoryFiles.cloneCommandLine(repo, "refs/heads/main");
        assertEquals(
                List.of("git", "clone", "--depth", "1", "--branch", "main", "--", repo, "."),
                cmd);
        String joined = String.join(" ", cmd);
        assertFalse(joined.contains("@"));
        assertFalse(joined.contains(password));
        assertFalse(joined.contains("oauth2"));
        assertFalse(joined.contains("user:pass"));
    }

    @Test
    public void cloneEnv_setsAskpassWithoutEmbeddingSecrets() {
        Map<String, String> withAuth = GitRepositoryFiles.cloneEnv("/tmp/askpass.sh");
        assertEquals("/tmp/askpass.sh", withAuth.get("GIT_ASKPASS"));
        assertEquals("0", withAuth.get("GIT_TERMINAL_PROMPT"));
        assertEquals("never", withAuth.get("GCM_INTERACTIVE"));
        assertFalse(withAuth.values().stream().anyMatch(v -> v.contains("s3cret")));

        Map<String, String> publicClone = GitRepositoryFiles.cloneEnv(null);
        assertFalse(publicClone.containsKey("GIT_ASKPASS"));
        assertEquals("0", publicClone.get("GIT_TERMINAL_PROMPT"));
    }

    @Test
    public void askpassScriptContent_hasNoEmbeddedCredentials() {
        String unix = GitRepositoryFiles.askpassScriptContent(true);
        String win = GitRepositoryFiles.askpassScriptContent(false);
        assertTrue(unix.startsWith("#!/bin/sh"));
        assertTrue(unix.contains("askpass.username"));
        assertTrue(unix.contains("askpass.password"));
        assertFalse(unix.contains("oauth2"));
        assertFalse(unix.contains("s3cret"));
        assertTrue(win.toLowerCase().contains("@echo off"));
        assertTrue(win.contains("askpass.username"));
        assertTrue(win.contains("askpass.password"));
        assertFalse(win.contains("s3cret"));
    }

    @Test
    public void scrubSecrets_redactsUserinfoAndPassword() {
        PortainerCredentials.GitAuth auth = new PortainerCredentials.GitAuth("oauth2", "s3cret:token");
        String raw = "fatal: https://oauth2:s3cret%3Atoken@gitlab.example/group/values.git/info/refs not found";
        String scrubbed = GitRepositoryFiles.scrubSecrets(raw, auth);
        assertTrue(!scrubbed.contains("s3cret"));
        assertTrue(scrubbed.contains("***@gitlab.example") || scrubbed.contains("***"));
    }

    @Test
    public void readFile_rejectsBlockedHost_withoutOverride() {
        GitRepositoryFiles.testOverride = null;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.readFile(
                        "http://169.254.169.254/repo.git",
                        "main",
                        "values.yaml",
                        null,
                        null,
                        null,
                        null));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not allowed") || msg.contains("resolv"), msg);
    }

    @Test
    public void readFile_rejectsUnresolvableHost_withoutOverride() {
        GitRepositoryFiles.testOverride = null;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.readFile(
                        "https://gitlab.example/group/values.git",
                        "main",
                        "values.yaml",
                        null,
                        null,
                        null,
                        null));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("resolv") || msg.contains("not allowed"), msg);
    }

    @Test
    public void readFile_testOverride_bypassesHostCheck() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        GitRepositoryFiles.testOverride = req -> {
            called.set(true);
            assertEquals("http://169.254.169.254/repo.git", req.repositoryUrl);
            return "values: {}\n";
        };
        String content = GitRepositoryFiles.readFile(
                "http://169.254.169.254/repo.git",
                "main",
                "values.yaml",
                null,
                null,
                null,
                null);
        assertEquals("values: {}\n", content);
        assertTrue(called.get());
    }

    @Test
    public void listConfigFiles_rejectsBlockedHost_withoutOverride() {
        GitRepositoryFiles.listTestOverride = null;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.listConfigFiles(
                        "http://169.254.169.254/configs.git",
                        "main",
                        "configs",
                        "**/*",
                        null,
                        null,
                        null,
                        null));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not allowed") || msg.contains("resolv"), msg);
    }

    @Test
    public void listConfigFiles_rejectsUnresolvableHost_withoutOverride() {
        GitRepositoryFiles.listTestOverride = null;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.listConfigFiles(
                        "https://gitlab.example/group/configs.git",
                        "main",
                        "configs",
                        "**/*",
                        null,
                        null,
                        null,
                        null));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("resolv") || msg.contains("not allowed"), msg);
    }

    @Test
    public void listConfigFiles_listTestOverride_bypassesHostCheck() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        GitRepositoryFiles.listTestOverride = req -> {
            called.set(true);
            assertEquals("http://169.254.169.254/configs.git", req.repositoryUrl);
            return List.of(new SwarmConfigFile("app.json", "{}".getBytes()));
        };
        List<SwarmConfigFile> files = GitRepositoryFiles.listConfigFiles(
                "http://169.254.169.254/configs.git",
                "main",
                "configs",
                "**/*",
                null,
                null,
                null,
                null);
        assertEquals(1, files.size());
        assertEquals("app.json", files.get(0).relativePath);
        assertTrue(called.get());
    }
}
