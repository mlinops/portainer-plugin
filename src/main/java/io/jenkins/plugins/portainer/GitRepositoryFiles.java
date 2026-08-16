package io.jenkins.plugins.portainer;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Reads a single relative file from a Git repository via shallow {@code git clone}.
 * Used when Portainer only accepts string content (Helm {@code values}) and cannot clone for us.
 * Reuses {@link GitRepositoryUrl} / {@link PortainerCredentials.GitAuth} patterns from Stack/Manifest.
 * Never logs credentials, askpass contents, clone URLs, or file bodies.
 * Private clones use {@code GIT_ASKPASS} so the password is not present in process argv.
 */
final class GitRepositoryFiles {

    /** When non-null, {@link #readFile} returns this result instead of cloning. */
    static volatile Function<FetchRequest, String> testOverride;

    /** When non-null, {@link #listConfigFiles} returns this result instead of cloning. */
    static volatile Function<ListRequest, List<SwarmConfigFile>> listTestOverride;

    private static final String ASKPASS_USERNAME_FILE = "askpass.username";
    private static final String ASKPASS_PASSWORD_FILE = "askpass.password";
    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private GitRepositoryFiles() {
    }

    static final class FetchRequest {
        final String repositoryUrl;
        final String reference;
        final String relativePath;

        FetchRequest(String repositoryUrl, String reference, String relativePath) {
            this.repositoryUrl = repositoryUrl;
            this.reference = reference;
            this.relativePath = relativePath;
        }
    }

    static final class ListRequest {
        final String repositoryUrl;
        final String reference;
        final String configPath;
        final String fileGlob;

        ListRequest(String repositoryUrl, String reference, String configPath, String fileGlob) {
            this.repositoryUrl = repositoryUrl;
            this.reference = reference;
            this.configPath = configPath;
            this.fileGlob = fileGlob;
        }
    }

    /**
     * Shallow-clone {@code repositoryUrl} at {@code reference} into a workspace temp dir and read
     * {@code relativePath}.
     *
     * @param auth optional; never logged
     * @return file content (may be empty string if the file is empty)
     */
    static String readFile(
            String repositoryUrl,
            String reference,
            String relativePath,
            PortainerCredentials.GitAuth auth,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener)
            throws IOException, InterruptedException {
        String repo = GitRepositoryUrl.normalize(repositoryUrl);
        String path = PortainerComposePath.normalize(relativePath, "Values file path");
        String ref = reference == null || reference.isBlank()
                ? PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE
                : reference.trim();

        Function<FetchRequest, String> override = testOverride;
        if (override != null) {
            return override.apply(new FetchRequest(repo, ref, path));
        }
        ConnectionTester.assertHostAllowed(repo, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
        if (workspace == null) {
            throw new IOException("Workspace is required to fetch Helm values from Git.");
        }
        if (launcher == null) {
            throw new IOException("Launcher is required to fetch Helm values from Git.");
        }

        FilePath tmp = workspace.createTempDir("portainer-helm-values", null);
        try {
            FilePath checkout = shallowClone(repo, ref, auth, tmp, launcher, listener, "values");
            FilePath file = checkout.child(path);
            if (!file.exists()) {
                throw new IOException("Values file not found in repository: " + path);
            }
            if (file.isDirectory()) {
                throw new IOException("Values file path is a directory: " + path);
            }
            return file.readToString();
        } finally {
            try {
                tmp.deleteRecursive();
            } catch (IOException | InterruptedException cleanup) {
                if (cleanup instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // best-effort cleanup
            }
        }
    }

    /**
     * Shallow-clone {@code repositoryUrl} at {@code reference} and list files under {@code configPath}
     * matching {@code fileGlob} (Ant-style, relative to {@code configPath}).
     *
     * @param auth optional; never logged
     * @return config files with path relative to {@code configPath}
     */
    static List<SwarmConfigFile> listConfigFiles(
            String repositoryUrl,
            String reference,
            String configPath,
            String fileGlob,
            PortainerCredentials.GitAuth auth,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener)
            throws IOException, InterruptedException {
        String repo = GitRepositoryUrl.normalize(repositoryUrl);
        String dir = SwarmConfigNaming.normalizeConfigPath(configPath);
        String glob = SwarmConfigNaming.normalizeFileGlob(fileGlob);
        String ref = defaultGitReference(reference);

        Function<ListRequest, List<SwarmConfigFile>> override = listTestOverride;
        if (override != null) {
            return override.apply(new ListRequest(repo, ref, dir, glob));
        }
        ConnectionTester.assertHostAllowed(repo, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
        requireCloneContext(workspace, launcher, "Swarm configs");

        FilePath tmp = workspace.createTempDir("portainer-swarm-configs", null);
        try {
            FilePath checkout = shallowClone(repo, ref, auth, tmp, launcher, listener, "config");
            return listMatchingConfigFiles(requireConfigDirectory(checkout.child(dir), dir), glob);
        } finally {
            deleteTempQuietly(tmp);
        }
    }

    private static String defaultGitReference(String reference) {
        return reference == null || reference.isBlank()
                ? PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE
                : reference.trim();
    }

    private static void requireCloneContext(FilePath workspace, Launcher launcher, String label)
            throws IOException {
        if (workspace == null) {
            throw new IOException("Workspace is required to fetch " + label + " from Git.");
        }
        if (launcher == null) {
            throw new IOException("Launcher is required to fetch " + label + " from Git.");
        }
    }

    private static FilePath requireConfigDirectory(FilePath root, String dir)
            throws IOException, InterruptedException {
        if (!root.exists()) {
            throw new IOException("Config path not found in repository: " + dir);
        }
        if (!root.isDirectory()) {
            throw new IOException("Config path is not a directory: " + dir);
        }
        return root;
    }

    private static List<SwarmConfigFile> listMatchingConfigFiles(FilePath root, String glob)
            throws IOException, InterruptedException {
        FilePath[] matches = root.list(glob, "");
        if (matches == null || matches.length == 0) {
            return Collections.emptyList();
        }
        List<SwarmConfigFile> out = new ArrayList<>();
        for (FilePath file : matches) {
            SwarmConfigFile entry = toConfigFile(root, file);
            if (entry != null) {
                out.add(entry);
            }
        }
        out.sort((a, b) -> a.relativePath.compareTo(b.relativePath));
        return out;
    }

    private static SwarmConfigFile toConfigFile(FilePath root, FilePath file)
            throws IOException, InterruptedException {
        if (file == null || !file.exists() || file.isDirectory()) {
            return null;
        }
        String normalized = relativeRemotePath(root, file);
        if (normalized.isEmpty() || normalized.endsWith("/")) {
            return null;
        }
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (baseName.startsWith(".")) {
            return null;
        }
        try (InputStream in = file.read()) {
            return new SwarmConfigFile(normalized, in.readAllBytes());
        }
    }

    private static void deleteTempQuietly(FilePath tmp) {
        try {
            tmp.deleteRecursive();
        } catch (IOException | InterruptedException cleanup) {
            if (cleanup instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // best-effort cleanup
        }
    }

    /**
     * {@code git clone} argv: repository URL must not contain userinfo; auth uses {@code GIT_ASKPASS}.
     */
    static List<String> cloneCommandLine(String repositoryUrl, String reference) {
        String checkout = shortRefForClone(reference);
        List<String> cmd = new ArrayList<>(9);
        cmd.add("git");
        cmd.add("clone");
        cmd.add("--depth");
        cmd.add("1");
        cmd.add("--branch");
        cmd.add(checkout);
        cmd.add("--");
        cmd.add(repositoryUrl);
        cmd.add(".");
        return cmd;
    }

    /**
     * Env overrides for a shallow clone. Password is never placed in the environment.
     *
     * @param askpassRemote absolute remote path to the askpass helper, or {@code null} when unauthenticated
     */
    static Map<String, String> cloneEnv(String askpassRemote) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
        if (askpassRemote != null && !askpassRemote.isBlank()) {
            env.put("GIT_ASKPASS", askpassRemote);
        }
        return env;
    }

    /**
     * Askpass helper body (no credentials). Username/password are read from sibling files so
     * special characters in secrets do not need shell escaping.
     */
    static String askpassScriptContent(boolean unix) {
        if (unix) {
            return "#!/bin/sh\n"
                    + "DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
                    + "case \"$1\" in\n"
                    + "*[Uu]sername*)\n"
                    + "  cat \"$DIR/" + ASKPASS_USERNAME_FILE + "\"\n"
                    + "  ;;\n"
                    + "*)\n"
                    + "  cat \"$DIR/" + ASKPASS_PASSWORD_FILE + "\"\n"
                    + "  ;;\n"
                    + "esac\n";
        }
        return "@echo off\r\n"
                + "echo.%*| find /I \"Username\" >NUL\r\n"
                + "if errorlevel 1 (\r\n"
                + "  type \"%~dp0" + ASKPASS_PASSWORD_FILE + "\"\r\n"
                + ") else (\r\n"
                + "  type \"%~dp0" + ASKPASS_USERNAME_FILE + "\"\r\n"
                + ")\r\n";
    }

    /**
     * Shallow-clone into an empty {@code tmp/repo} directory. Askpass sidecars (if any) stay under
     * {@code tmp} so the clone target remains empty — {@code git clone … .} refuses non-empty dirs.
     *
     * @return checkout directory containing the repository files
     */
    private static FilePath shallowClone(
            String repo,
            String ref,
            PortainerCredentials.GitAuth auth,
            FilePath tmp,
            Launcher launcher,
            TaskListener listener,
            String failureLabel)
            throws IOException, InterruptedException {
        String askpassRemote = null;
        if (auth != null) {
            askpassRemote = writeAskpass(tmp, auth, launcher.isUnix()).getRemote();
        }
        FilePath checkout = tmp.child("repo");
        checkout.mkdirs();
        ArgumentListBuilder args = new ArgumentListBuilder();
        for (String part : cloneCommandLine(repo, ref)) {
            args.add(part);
        }
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = launcher.launch()
                .cmds(args)
                .envs(cloneEnv(askpassRemote))
                .pwd(checkout)
                .stdout(OutputStream.nullOutputStream())
                .stderr(err)
                .quiet(true)
                .start()
                .joinWithTimeout(10, TimeUnit.MINUTES, listener);
        if (code != 0) {
            String detail = scrubSecrets(err.toString(StandardCharsets.UTF_8), auth);
            if (detail.length() > 240) {
                detail = detail.substring(0, 240) + "…";
            }
            throw new IOException(
                    "Failed to clone " + failureLabel + " repository (git exit " + code
                            + "). Check URL, reference, credentials, and that git is on PATH."
                            + (detail.isBlank() ? "" : " Detail: " + detail.replaceAll("\\s+", " ").trim()));
        }
        return checkout;
    }

    /**
     * Write askpass helper + credential sidecar files under {@code tmp}. Caller deletes {@code tmp}
     * in {@code finally}. Does not put secrets in argv or process environment.
     */
    static FilePath writeAskpass(FilePath tmp, PortainerCredentials.GitAuth auth, boolean unix)
            throws IOException, InterruptedException {
        if (auth == null) {
            throw new IllegalArgumentException("GitAuth is required to write askpass");
        }
        tmp.child(ASKPASS_USERNAME_FILE).write(auth.username == null ? "" : auth.username, UTF_8);
        tmp.child(ASKPASS_PASSWORD_FILE).write(auth.password == null ? "" : auth.password, UTF_8);
        FilePath askpass = tmp.child(unix ? "askpass.sh" : "askpass.bat");
        askpass.write(askpassScriptContent(unix), UTF_8);
        if (unix) {
            askpass.chmod(0755);
        }
        return askpass;
    }

    static String scrubSecrets(String text, PortainerCredentials.GitAuth auth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        // Drop any userinfo segment in URLs that may appear in git stderr.
        out = out.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1***@");
        if (auth != null) {
            if (auth.password != null && !auth.password.isEmpty()) {
                out = out.replace(auth.password, "***");
                out = out.replace(encodeUserInfo(auth.password), "***");
            }
            if (auth.username != null && !auth.username.isEmpty()) {
                out = out.replace(encodeUserInfo(auth.username) + ":***", "***:***");
            }
        }
        return out;
    }

    private static String relativeRemotePath(FilePath base, FilePath file) {
        String baseRemote = base.getRemote();
        String fileRemote = file.getRemote();
        if (baseRemote == null || fileRemote == null) {
            return file.getName();
        }
        if (fileRemote.equals(baseRemote)) {
            return "";
        }
        if (fileRemote.startsWith(baseRemote)) {
            String rel = fileRemote.substring(baseRemote.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) {
                rel = rel.substring(1);
            }
            return rel.replace('\\', '/');
        }
        return file.getName();
    }

    /**
     * Map {@code refs/heads/…} / {@code refs/tags/…} to a short name for {@code git clone --branch}.
     */
    static String shortRefForClone(String reference) {
        if (reference == null || reference.isBlank()) {
            return "main";
        }
        String ref = reference.trim();
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }

    private static String encodeUserInfo(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() * 2);
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
