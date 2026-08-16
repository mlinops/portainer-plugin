package io.jenkins.plugins.portainer;

import hudson.FilePath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GitRepositoryFilesTest {

    @TempDir
    Path tempDir;

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
    public void defaultGitReference_blankUsesStackDefault() {
        assertEquals(
                PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE,
                GitRepositoryFiles.defaultGitReference(null));
        assertEquals(
                PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE,
                GitRepositoryFiles.defaultGitReference("  "));
        assertEquals("refs/heads/dev", GitRepositoryFiles.defaultGitReference(" refs/heads/dev "));
    }

    @Test
    public void requireCloneContext_rejectsNullWorkspaceOrLauncher() {
        IOException ws = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.requireCloneContext(null, null, "Helm values"));
        assertTrue(ws.getMessage().contains("Workspace is required"));
        assertTrue(ws.getMessage().contains("Helm values"));

        FilePath workspace = new FilePath(tempDir.toFile());
        IOException launcher = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.requireCloneContext(workspace, null, "Swarm configs"));
        assertTrue(launcher.getMessage().contains("Launcher is required"));
        assertTrue(launcher.getMessage().contains("Swarm configs"));
    }

    @Test
    public void requireConfigDirectory_rejectsMissingAndNonDirectory() throws Exception {
        FilePath root = new FilePath(tempDir.toFile());
        FilePath missing = root.child("nope");
        IOException notFound = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.requireConfigDirectory(missing, "configs"));
        assertTrue(notFound.getMessage().contains("Config path not found"));

        FilePath file = root.child("not-a-dir.txt");
        file.write("x", StandardCharsets.UTF_8.name());
        IOException notDir = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.requireConfigDirectory(file, "configs"));
        assertTrue(notDir.getMessage().contains("not a directory"));

        FilePath dir = root.child("configs");
        dir.mkdirs();
        assertEquals(dir, GitRepositoryFiles.requireConfigDirectory(dir, "configs"));
    }

    @Test
    public void listMatchingConfigFiles_skipsDotfilesAndSorts() throws Exception {
        FilePath root = new FilePath(tempDir.toFile());
        root.child("b.json").write("{\"b\":1}", StandardCharsets.UTF_8.name());
        root.child("a.json").write("{\"a\":1}", StandardCharsets.UTF_8.name());
        root.child(".hidden.json").write("{}", StandardCharsets.UTF_8.name());
        root.child("subdir").mkdirs();

        List<SwarmConfigFile> files = GitRepositoryFiles.listMatchingConfigFiles(root, "*.json");
        assertEquals(2, files.size());
        assertEquals("a.json", files.get(0).relativePath);
        assertEquals("b.json", files.get(1).relativePath);
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), files.get(0).content);

        assertTrue(GitRepositoryFiles.listMatchingConfigFiles(root, "*.yaml").isEmpty());
    }

    @Test
    public void toConfigFile_skipsNullDirectoryAndDotfile() throws Exception {
        FilePath root = new FilePath(tempDir.toFile());
        assertNull(GitRepositoryFiles.toConfigFile(root, null));

        FilePath dir = root.child("d");
        dir.mkdirs();
        assertNull(GitRepositoryFiles.toConfigFile(root, dir));

        FilePath dot = root.child(".env");
        dot.write("x=1", StandardCharsets.UTF_8.name());
        assertNull(GitRepositoryFiles.toConfigFile(root, dot));

        FilePath ok = root.child("app.json");
        ok.write("{}", StandardCharsets.UTF_8.name());
        SwarmConfigFile entry = GitRepositoryFiles.toConfigFile(root, ok);
        assertEquals("app.json", entry.relativePath);
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), entry.content);
    }

    @Test
    public void deleteTempQuietly_removesDirectory() throws Exception {
        FilePath tmp = new FilePath(tempDir.resolve("to-delete").toFile());
        tmp.mkdirs();
        tmp.child("f.txt").write("x", StandardCharsets.UTF_8.name());
        assertTrue(tmp.exists());
        GitRepositoryFiles.deleteTempQuietly(tmp);
        assertFalse(tmp.exists());
    }

    @Test
    public void writeAskpass_writesSidecars() throws Exception {
        FilePath tmp = new FilePath(tempDir.toFile());
        PortainerCredentials.GitAuth auth = new PortainerCredentials.GitAuth("user", "pass");
        FilePath askpass = GitRepositoryFiles.writeAskpass(tmp, auth, true);
        assertTrue(askpass.getName().endsWith(".sh"));
        assertEquals("user", tmp.child("askpass.username").readToString());
        assertEquals("pass", tmp.child("askpass.password").readToString());
        assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.writeAskpass(tmp, null, true));
    }

    @Test
    public void listConfigFiles_defaultReferencePassedToOverride() throws Exception {
        AtomicReference<String> seenRef = new AtomicReference<>();
        GitRepositoryFiles.listTestOverride = req -> {
            seenRef.set(req.reference);
            return List.of();
        };
        GitRepositoryFiles.listConfigFiles(
                "http://127.0.0.1/configs.git",
                "  ",
                "configs",
                "**/*",
                null,
                null,
                null,
                null);
        assertEquals(PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE, seenRef.get());
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
