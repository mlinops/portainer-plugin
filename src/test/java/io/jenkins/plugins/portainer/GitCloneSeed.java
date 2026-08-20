package io.jenkins.plugins.portainer;

import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JenkinsRule helper: intercept {@code git clone} on the build {@link Launcher} and seed the
 * checkout directory. Used when the step is constructed from Pipeline DSL (no instance inject).
 * Production {@link GitRepositoryFiles} still runs SSRF and the clone argv;
 * tests use {@code http://127.0.0.1/…} with {@link ConnectionTester#ALLOW_LOOPBACK_FOR_TESTS_PROP}.
 */
final class GitCloneSeed {

    static final String LOOPBACK_VALUES = "http://127.0.0.1/values.git";
    static final String LOOPBACK_CONFIGS = "http://127.0.0.1/configs.git";

    @FunctionalInterface
    interface Checkout {
        void seed(FilePath checkout) throws IOException, InterruptedException;
    }

    private static final AtomicReference<Checkout> CHECKOUT = new AtomicReference<>();

    private GitCloneSeed() {
    }

    static void set(Checkout checkout) {
        CHECKOUT.set(checkout);
    }

    static void clear() {
        CHECKOUT.set(null);
    }

    static void write(FilePath checkout, String relativePath, byte[] content)
            throws IOException, InterruptedException {
        FilePath file = checkout.child(relativePath);
        FilePath parent = file.getParent();
        if (parent != null) {
            parent.mkdirs();
        }
        try (OutputStream os = file.write()) {
            os.write(content);
        }
    }

    public abstract static class Decorator extends LauncherDecorator {
        @Override
        public Launcher decorate(Launcher launcher, Node node) {
            return new SeedingLauncher(launcher);
        }
    }

    private static final class SeedingLauncher extends Launcher.DecoratedLauncher {
        SeedingLauncher(Launcher inner) {
            super(inner);
        }

        @Override
        public Proc launch(ProcStarter starter) throws IOException {
            Checkout seed = CHECKOUT.get();
            if (seed != null && isGitClone(starter) && starter.pwd() != null) {
                try {
                    seed.seed(starter.pwd());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return completed(0);
            }
            return super.launch(starter);
        }
    }

    private static boolean isGitClone(Launcher.ProcStarter starter) {
        List<String> cmds = starter.cmds();
        if (cmds == null || cmds.size() < 2) {
            return false;
        }
        String exe = cmds.get(0);
        boolean git = "git".equals(exe) || (exe != null && exe.endsWith("git"))
                || (exe != null && exe.endsWith("git.exe"));
        return git && cmds.contains("clone");
    }

    private static Proc completed(int exitCode) {
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
