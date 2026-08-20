package io.jenkins.plugins.portainer;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GitRepositoryFilesTest {

    private static final String LOOPBACK_REPO = "http://127.0.0.1/values.git";
    private static final String LOOPBACK_CONFIGS = "http://127.0.0.1/configs.git";

    @TempDir
    Path tempDir;

    @AfterEach
    public void clearLoopback() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    private static GitRepositoryFiles.CloneContext cloneCtx(
            PortainerCredentials.GitAuth auth,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener) {
        return new GitRepositoryFiles.CloneContext(auth, workspace, launcher, listener);
    }

    /** Fake {@code git} via {@link Launcher}: no real process, optional checkout seeding. */
    @FunctionalInterface
    private interface CheckoutSeeder {
        void seed(FilePath checkout) throws IOException, InterruptedException;
    }

    private static final class StubGitLauncher extends Launcher.DummyLauncher {
        private final int exitCode;
        private final String stderrText;
        private final CheckoutSeeder seedCheckout;

        StubGitLauncher(int exitCode, String stderrText, CheckoutSeeder seedCheckout) {
            super(TaskListener.NULL);
            this.exitCode = exitCode;
            this.stderrText = stderrText;
            this.seedCheckout = seedCheckout;
        }

        @Override
        public Proc launch(ProcStarter starter) throws IOException {
            if (stderrText != null && !stderrText.isEmpty() && starter.stderr() != null) {
                starter.stderr().write(stderrText.getBytes(StandardCharsets.UTF_8));
            }
            if (exitCode == 0 && seedCheckout != null && starter.pwd() != null) {
                try {
                    seedCheckout.seed(starter.pwd());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            return new Proc() {
                @Override
                public void kill() {
                    throw new UnsupportedOperationException("not used in stub");
                }

                @Override
                public int join() {
                    return exitCode;
                }

                @Override
                public boolean isAlive() {
                    return false;
                }

                @Override
                public InputStream getStdout() {
                    return InputStream.nullInputStream();
                }

                @Override
                public InputStream getStderr() {
                    return InputStream.nullInputStream();
                }

                @Override
                public OutputStream getStdin() {
                    return OutputStream.nullOutputStream();
                }
            };
        }
    }

    private static void allowLoopback() {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
    }

    private static void seedValuesFile(FilePath checkout, String relativePath, String content)
            throws IOException, InterruptedException {
        FilePath file = checkout.child(relativePath);
        FilePath parent = file.getParent();
        if (parent != null) {
            parent.mkdirs();
        }
        file.write(content, StandardCharsets.UTF_8.name());
    }

    @Test
    public void shortRefForClone_stripsRefsPrefix() {
        assertEquals("main", GitRepositoryFiles.shortRefForClone("refs/heads/main"));
        assertEquals("v1.2.3", GitRepositoryFiles.shortRefForClone("refs/tags/v1.2.3"));
        assertEquals("feature/x", GitRepositoryFiles.shortRefForClone("feature/x"));
        assertEquals("main", GitRepositoryFiles.shortRefForClone(" "));
        assertEquals("main", GitRepositoryFiles.shortRefForClone(null));
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
    public void toConfigFile_nestedRelativePath() throws Exception {
        FilePath root = new FilePath(tempDir.resolve("cfg-root").toFile());
        root.mkdirs();
        FilePath nested = root.child("sub").child("nested.json");
        nested.getParent().mkdirs();
        nested.write("{\"n\":1}", StandardCharsets.UTF_8.name());
        SwarmConfigFile entry = GitRepositoryFiles.toConfigFile(root, nested);
        assertEquals("sub/nested.json", entry.relativePath.replace('\\', '/'));
        assertArrayEquals("{\"n\":1}".getBytes(StandardCharsets.UTF_8), entry.content);
    }

    @Test
    public void toConfigFile_samePathAsRoot_returnsNull() throws Exception {
        FilePath file = new FilePath(tempDir.resolve("same.json").toFile());
        file.write("{}", StandardCharsets.UTF_8.name());
        assertNull(GitRepositoryFiles.toConfigFile(file, file));
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
    public void writeAskpass_windowsBatAndNullCredentialFields() throws Exception {
        FilePath tmp = new FilePath(tempDir.resolve("askpass-win").toFile());
        tmp.mkdirs();
        PortainerCredentials.GitAuth auth = new PortainerCredentials.GitAuth(null, null);
        FilePath askpass = GitRepositoryFiles.writeAskpass(tmp, auth, false);
        assertTrue(askpass.getName().endsWith(".bat"));
        assertEquals("", tmp.child("askpass.username").readToString());
        assertEquals("", tmp.child("askpass.password").readToString());
        assertTrue(askpass.readToString().toLowerCase().contains("@echo off"));
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
    public void cloneEnv_blankAskpassOmitsGitAskpass() {
        Map<String, String> blank = GitRepositoryFiles.cloneEnv("   ");
        assertFalse(blank.containsKey("GIT_ASKPASS"));
        assertEquals("0", blank.get("GIT_TERMINAL_PROMPT"));
        assertEquals("never", blank.get("GCM_INTERACTIVE"));
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
    public void scrubSecrets_nullEmptyAuthAndEncodePaths() {
        assertEquals("", GitRepositoryFiles.scrubSecrets(null, null));
        assertEquals("", GitRepositoryFiles.scrubSecrets("", null));
        assertEquals("plain", GitRepositoryFiles.scrubSecrets("plain", null));

        PortainerCredentials.GitAuth emptyPass = new PortainerCredentials.GitAuth("user", "");
        assertEquals("no-secret", GitRepositoryFiles.scrubSecrets("no-secret", emptyPass));

        PortainerCredentials.GitAuth nullPass = new PortainerCredentials.GitAuth("user", null);
        assertEquals("still-plain", GitRepositoryFiles.scrubSecrets("still-plain", nullPass));

        PortainerCredentials.GitAuth special =
                new PortainerCredentials.GitAuth("oauth2", "p@ss:word");
        String raw = "fatal: https://oauth2:p%40ss%3Aword@gitlab.example/r.git failed p@ss:word";
        String scrubbed = GitRepositoryFiles.scrubSecrets(raw, special);
        assertFalse(scrubbed.contains("p@ss"));
        assertFalse(scrubbed.contains("p%40ss"));
        assertTrue(scrubbed.contains("***"));

        PortainerCredentials.GitAuth userOnly = new PortainerCredentials.GitAuth("alice+ci", "tok");
        String withEncodedUser = "https://alice%2Bci:***@gitlab.example/r.git tok";
        String scrubbedUser = GitRepositoryFiles.scrubSecrets(withEncodedUser, userOnly);
        assertTrue(scrubbedUser.contains("***:***") || scrubbedUser.contains("***"));
        assertFalse(scrubbedUser.contains("tok"));
    }

    @Test
    public void relativeRemotePath_equalBackslashAndOutsideBase() {
        // Avoid null remotes: FilePath.getName() NPEs when remote is null.
        FilePath base = new FilePath((VirtualChannel) null, "C:\\repo");
        assertEquals("", GitRepositoryFiles.relativeRemotePath(base, base));

        FilePath nested = new FilePath((VirtualChannel) null, "C:\\repo\\sub\\a.json");
        assertEquals("sub/a.json", GitRepositoryFiles.relativeRemotePath(base, nested));

        FilePath outside = new FilePath((VirtualChannel) null, "D:\\other\\only-name.txt");
        assertEquals("only-name.txt", GitRepositoryFiles.relativeRemotePath(base, outside));
    }

    @Test
    public void readFile_rejectsBlockedHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.readFile(
                        "http://169.254.169.254/repo.git",
                        "main",
                        "values.yaml",
                        cloneCtx(null, null, null, null)));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not allowed") || msg.contains("resolv"), msg);
    }

    @Test
    public void readFile_rejectsUnresolvableHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.readFile(
                        "https://gitlab.example/group/values.git",
                        "main",
                        "values.yaml",
                        cloneCtx(null, null, null, null)));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("resolv") || msg.contains("not allowed"), msg);
    }

    @Test
    public void listConfigFiles_rejectsBlockedHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.listConfigFiles(
                        "http://169.254.169.254/configs.git",
                        "main",
                        "configs",
                        "**/*",
                        cloneCtx(null, null, null, null)));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not allowed") || msg.contains("resolv"), msg);
    }

    @Test
    public void listConfigFiles_rejectsUnresolvableHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> GitRepositoryFiles.listConfigFiles(
                        "https://gitlab.example/group/configs.git",
                        "main",
                        "configs",
                        "**/*",
                        cloneCtx(null, null, null, null)));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("resolv") || msg.contains("not allowed"), msg);
    }

    @Test
    public void readFile_cloneSuccess_returnsContent() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-ok").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(
                0, null, checkout -> seedValuesFile(checkout, "values.yaml", "replicaCount: 2\n"));
        String content = GitRepositoryFiles.readFile(
                LOOPBACK_REPO,
                "refs/heads/main",
                "values.yaml",
                cloneCtx(null, workspace, launcher, TaskListener.NULL));
        assertEquals("replicaCount: 2\n", content);
    }

    @Test
    public void readFile_cloneSuccess_missingFile() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-missing").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(0, null, checkout -> {
        });
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "missing.yaml",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("Values file not found"));
    }

    @Test
    public void readFile_cloneSuccess_pathIsDirectory() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-dir").toFile());
        workspace.mkdirs();
        // Path must end with .yml/.yaml for PortainerComposePath.normalize; seed as a directory.
        Launcher launcher = new StubGitLauncher(0, null, checkout -> {
            checkout.child("values.yaml").mkdirs();
        });
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("directory"));
    }

    @Test
    public void readFile_cloneFailure_truncatesLongStderrAndScrubs() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-fail").toFile());
        workspace.mkdirs();
        String password = "s3cret:token";
        String longDetail = "fatal: https://oauth2:" + password + "@gitlab.example/r.git "
                + "x".repeat(300);
        PortainerCredentials.GitAuth auth = new PortainerCredentials.GitAuth("oauth2", password);
        Launcher launcher = new StubGitLauncher(128, longDetail, null);
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(auth, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("git exit 128"));
        assertTrue(ex.getMessage().contains("Detail:"));
        assertTrue(ex.getMessage().contains("…"));
        assertFalse(ex.getMessage().contains(password));
    }

    @Test
    public void readFile_cloneFailure_blankStderrOmitsDetail() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-blank").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(1, "", null);
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.readFile(
                        LOOPBACK_REPO,
                        "main",
                        "values.yaml",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("git exit 1"));
        assertFalse(ex.getMessage().contains("Detail:"));
    }

    @Test
    public void listConfigFiles_cloneSuccess_listsConfigs() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-configs").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(0, null, checkout -> {
            FilePath dir = checkout.child("configs");
            dir.mkdirs();
            dir.child("b.json").write("{\"b\":1}", StandardCharsets.UTF_8.name());
            dir.child("a.json").write("{\"a\":1}", StandardCharsets.UTF_8.name());
        });
        List<SwarmConfigFile> files = GitRepositoryFiles.listConfigFiles(
                LOOPBACK_CONFIGS,
                "main",
                "configs",
                "*.json",
                cloneCtx(null, workspace, launcher, TaskListener.NULL));
        assertEquals(2, files.size());
        assertEquals("a.json", files.get(0).relativePath);
        assertEquals("b.json", files.get(1).relativePath);
    }

    @Test
    public void listConfigFiles_cloneFailure_usesConfigLabel() throws Exception {
        allowLoopback();
        FilePath workspace = new FilePath(tempDir.resolve("ws-cfg-fail").toFile());
        workspace.mkdirs();
        Launcher launcher = new StubGitLauncher(2, "permission denied", null);
        IOException ex = assertThrows(
                IOException.class,
                () -> GitRepositoryFiles.listConfigFiles(
                        LOOPBACK_CONFIGS,
                        "main",
                        "configs",
                        "**/*",
                        cloneCtx(null, workspace, launcher, TaskListener.NULL)));
        assertTrue(ex.getMessage().contains("config repository"));
        assertTrue(ex.getMessage().contains("Detail: permission denied"));
    }
}
