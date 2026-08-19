package io.jenkins.plugins.portainer;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Freestyle / Pipeline build step: ensure Docker Swarm configs from Git via Portainer
 * ({@code @Symbol("portainerStackConfig")}; alias {@code portainerSwarmConfig}).
 * <p>
 * Content-addressed naming ({@code {basename}-{hash8}}); existing names are skipped (immutable configs).
 */
public class PortainerSwarmConfigBuilder extends Builder implements SimpleBuildStep {

    static final String NAMING_CONTENT_HASH = "contentHash";
    static final String LABEL_GIT_SHA = "jenkins.portainer.config/git.sha";

    private static final Logger LOGGER = Logger.getLogger(PortainerSwarmConfigBuilder.class.getName());

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;
    public static final String DEFAULT_REPOSITORY_REFERENCE = PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE;

    private final String endpointId;

    private String repositoryUrl = "";
    private String configPath = "";
    private String fileGlob = "**/*";
    private String repositoryReferenceName;
    private String gitCredentialsId;
    private String namingStrategy = NAMING_CONTENT_HASH;

    private String portainerConnectionMode;
    private String portainerUrl;
    private String portainerCredentialsId;
    private boolean verboseLogging;
    private boolean validateOnly;
    private boolean pruneOld;

    @DataBoundConstructor
    public PortainerSwarmConfigBuilder(String endpointId) {
        this.endpointId = endpointId == null ? "" : endpointId.trim();
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    @DataBoundSetter
    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl == null ? "" : repositoryUrl.trim();
    }

    public String getConfigPath() {
        return configPath;
    }

    @DataBoundSetter
    public void setConfigPath(String configPath) {
        this.configPath = configPath == null ? "" : configPath.trim();
    }

    public String getFileGlob() {
        return fileGlob == null || fileGlob.isBlank() ? "**/*" : fileGlob.trim();
    }

    @DataBoundSetter
    public void setFileGlob(String fileGlob) {
        this.fileGlob = fileGlob == null || fileGlob.isBlank() ? "**/*" : fileGlob.trim();
    }

    public String getRepositoryReferenceName() {
        return repositoryReferenceName;
    }

    @DataBoundSetter
    public void setRepositoryReferenceName(String repositoryReferenceName) {
        this.repositoryReferenceName =
                repositoryReferenceName == null || repositoryReferenceName.isBlank()
                        ? null
                        : repositoryReferenceName.trim();
    }

    public String getGitCredentialsId() {
        return gitCredentialsId;
    }

    @DataBoundSetter
    public void setGitCredentialsId(String gitCredentialsId) {
        this.gitCredentialsId =
                gitCredentialsId == null || gitCredentialsId.isBlank() ? null : gitCredentialsId.trim();
    }

    public String getNamingStrategy() {
        return normalizeNamingStrategy(namingStrategy);
    }

    @DataBoundSetter
    public void setNamingStrategy(String namingStrategy) {
        this.namingStrategy = normalizeNamingStrategy(namingStrategy);
    }

    public String getPortainerConnectionMode() {
        String mode = ConnectionMode.normalize(portainerConnectionMode, MODE_INHERIT);
        return ConnectionMode.isNone(mode) ? MODE_INHERIT : mode;
    }

    @DataBoundSetter
    public void setPortainerConnectionMode(String portainerConnectionMode) {
        String normalized = ConnectionMode.normalize(portainerConnectionMode, MODE_INHERIT);
        this.portainerConnectionMode = ConnectionMode.isNone(normalized) ? MODE_INHERIT : normalized;
    }

    public String getPortainerUrl() {
        return portainerUrl;
    }

    @DataBoundSetter
    public void setPortainerUrl(String portainerUrl) {
        this.portainerUrl = portainerUrl == null ? "" : portainerUrl.trim();
    }

    public String getPortainerCredentialsId() {
        return portainerCredentialsId;
    }

    @DataBoundSetter
    public void setPortainerCredentialsId(String portainerCredentialsId) {
        this.portainerCredentialsId =
                portainerCredentialsId == null || portainerCredentialsId.isBlank()
                        ? null
                        : portainerCredentialsId.trim();
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    @DataBoundSetter
    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    public boolean isValidateOnly() {
        return validateOnly;
    }

    @DataBoundSetter
    public void setValidateOnly(boolean validateOnly) {
        this.validateOnly = validateOnly;
    }

    public boolean isPruneOld() {
        return pruneOld;
    }

    @DataBoundSetter
    public void setPruneOld(boolean pruneOld) {
        this.pruneOld = pruneOld;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        return PortainerSteps.performFreestyle(build, launcher, listener, this);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        try (PortainerBuildLogger log = new PortainerBuildLogger(LOGGER, listener, verboseLogging)) {
            log.open(PortainerBuildLogger.TITLE_STACK_CONFIG);
            performBody(run, workspace, launcher, listener, log);
        }
    }

    private void performBody(
            Run<?, ?> run,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            PortainerBuildLogger log) throws InterruptedException, IOException {
        long startedNs = System.nanoTime();
        EnvVars buildEnv = run.getEnvironment(listener);

        final int endpoint = PortainerConnections.abortOn(
                log, () -> PortainerConnections.resolveEndpointId(endpointId, buildEnv));
        requireContentHashNaming(log);

        final String repoUrl = PortainerConnections.abortOn(log, () -> GitRepositoryUrl.normalize(repositoryUrl));
        final String configDir = PortainerConnections.abortOn(
                log, () -> SwarmConfigNaming.normalizeConfigPath(buildEnv.expand(configPath)));
        final String glob = PortainerConnections.abortOn(
                log, () -> SwarmConfigNaming.normalizeFileGlob(buildEnv.expand(getFileGlob())));
        final String gitRef = PortainerStackBuilder.resolveRepositoryReference(repositoryReferenceName, buildEnv);

        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        final PortainerConnections.Authenticated auth = PortainerConnections.resolveAuthenticated(
                cfg,
                getPortainerConnectionMode(),
                portainerUrl,
                portainerCredentialsId,
                run.getParent(),
                log);
        final ResolvedConnection connection = auth.connection;
        final String apiKey = auth.apiKey;
        Item item = run.getParent();

        PortainerCredentials.GitAuth gitAuth =
                PortainerConnections.resolveOptionalGitAuth(gitCredentialsId, item, log);

        // Portainer → Git → mutate
        PortainerBuildLogger.debugPortainerStart(log, connection, endpoint, null);

        try (PortainerClient client = new PortainerClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            PortainerConnections.runPreflight(client, connection, apiKey, endpoint, false, log);
            resolveSwarmOrAbort(client, connection, apiKey, endpoint, log);

            PortainerBuildLogger.logGitPreflight(log, gitRef, repoUrl, configDir, glob, null, null);
            List<SwarmConfigFile> files = listConfigFilesFromGit(
                    new GitConfigListRequest(
                            repoUrl,
                            gitRef,
                            configDir,
                            glob,
                            new GitRepositoryFiles.CloneContext(gitAuth, workspace, launcher, listener)),
                    log);

            log.info("Git path=" + configDir);
            log.info("Configs found in Git - " + files.size());
            logConfigBasenames(log, files);

            if (validateOnly) {
                logValidatePlan(log, files);
                summarize(log, startedNs, files.size(), 0, 0);
                return;
            }

            DesiredConfigs desiredConfigs = buildDesiredConfigs(files, log);

            SwarmNamedResource.Outcome outcome = SwarmNamedResource.ensure(
                    SwarmNamedResource.Kind.CONFIG,
                    () -> client.listDockerConfigs(connection.baseUrl, apiKey, endpoint),
                    (name, data, labels) -> client.createDockerConfig(
                            connection.baseUrl,
                            apiKey,
                            endpoint,
                            new PortainerClient.DockerConfigCreateRequest(name, data, labels)),
                    desiredConfigs.desired,
                    (labels, desiredItem) -> {
                        String gitSha = SwarmConfigNaming.labelGitSha(gitRef);
                        if (!gitSha.isBlank()) {
                            labels.put(LABEL_GIT_SHA, gitSha);
                        }
                    },
                    log);

            if (pruneOld) {
                SwarmNamedResource.pruneStaleByBaseLabel(
                        SwarmNamedResource.Kind.CONFIG,
                        id -> client.removeDockerConfig(connection.baseUrl, apiKey, endpoint, id),
                        outcome.listed,
                        outcome.ensured,
                        log);
            }

            if (!desiredConfigs.envKeys.isEmpty()) {
                run.addAction(new PortainerSwarmConfigEnvAction(desiredConfigs.envKeys));
            }

            summarize(log, startedNs, files.size(), outcome.created, outcome.skipped);
        }
    }

    private void requireContentHashNaming(PortainerBuildLogger log) throws AbortException {
        if (!NAMING_CONTENT_HASH.equals(getNamingStrategy())) {
            throw PortainerConnections.abort(
                    log, "Unsupported namingStrategy \"" + getNamingStrategy() + "\" — only contentHash is supported.");
        }
    }

    private static void resolveSwarmOrAbort(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            PortainerBuildLogger log) throws AbortException {
        try {
            client.resolveSwarmId(connection.baseUrl, apiKey, endpoint);
        } catch (IOException e) {
            throw PortainerConnections.abort(
                    log,
                    "Swarm preflight failed for endpoint " + endpoint + ": "
                            + PortainerConnections.truncateMessage(e),
                    e);
        }
    }

    private static List<SwarmConfigFile> listConfigFilesFromGit(
            GitConfigListRequest req, PortainerBuildLogger log) throws InterruptedException, IOException {
        List<SwarmConfigFile> files;
        try {
            files = GitRepositoryFiles.listConfigFiles(
                    req.repoUrl, req.gitRef, req.configDir, req.glob, req.cloneCtx);
        } catch (IOException e) {
            String msg = PortainerConnections.truncateMessage(e);
            if (msg.startsWith("Config path not found")
                    || msg.startsWith("Config path is not a directory")) {
                throw PortainerConnections.abort(log, msg, e);
            }
            throw PortainerConnections.abort(
                    log, "Failed to read config files from Git: " + msg, e);
        }
        if (files.isEmpty()) {
            throw PortainerConnections.abort(
                    log,
                    "No config files matched configPath=" + req.configDir + " fileGlob=" + req.glob
                            + " in repository " + req.repoUrl);
        }
        return files;
    }

    private static void logConfigBasenames(PortainerBuildLogger log, List<SwarmConfigFile> files) {
        List<String> configNames = new ArrayList<>();
        for (SwarmConfigFile file : files) {
            configNames.add(SwarmConfigNaming.basenameFromRelativePath(file.relativePath));
        }
        log.debug("Configs found in Git: " + PortainerBuildLogger.formatNameList(configNames));
    }

    private static DesiredConfigs buildDesiredConfigs(List<SwarmConfigFile> files, PortainerBuildLogger log)
            throws AbortException {
        Map<String, String> envKeys = new LinkedHashMap<>();
        List<SwarmNamedResource.Desired> desired = new ArrayList<>();
        for (SwarmConfigFile file : files) {
            String basename = SwarmConfigNaming.basenameFromRelativePath(file.relativePath);
            String hash = SwarmConfigNaming.hash8(file.content);
            String configName = SwarmConfigNaming.configName(basename, file.content);
            String envKey = SwarmConfigNaming.envKeyForBasename(basename);
            String previous = envKeys.put(envKey, configName);
            if (previous != null && !previous.equals(configName)) {
                throw PortainerConnections.abort(
                        log,
                        "Duplicate env key " + envKey + " from config files (keep unique basenames).");
            }
            desired.add(new SwarmNamedResource.Desired(basename, configName, hash, file.content));
        }
        return new DesiredConfigs(desired, envKeys);
    }

    private static final class GitConfigListRequest {
        final String repoUrl;
        final String gitRef;
        final String configDir;
        final String glob;
        final GitRepositoryFiles.CloneContext cloneCtx;

        GitConfigListRequest(
                String repoUrl,
                String gitRef,
                String configDir,
                String glob,
                GitRepositoryFiles.CloneContext cloneCtx) {
            this.repoUrl = repoUrl;
            this.gitRef = gitRef;
            this.configDir = configDir;
            this.glob = glob;
            this.cloneCtx = cloneCtx;
        }
    }

    private static final class DesiredConfigs {
        final List<SwarmNamedResource.Desired> desired;
        final Map<String, String> envKeys;

        DesiredConfigs(List<SwarmNamedResource.Desired> desired, Map<String, String> envKeys) {
            this.desired = desired;
            this.envKeys = envKeys;
        }
    }

    private void logValidatePlan(PortainerBuildLogger log, List<SwarmConfigFile> files) {
        log.info("Validate-only — skipping Docker config mutations");
        for (SwarmConfigFile file : files) {
            String basename = SwarmConfigNaming.basenameFromRelativePath(file.relativePath);
            String configName = SwarmConfigNaming.configName(basename, file.content);
            log.debug("(would create) " + configName);
        }
    }

    private void summarize(
            PortainerBuildLogger log,
            long startedNs,
            int files,
            int created,
            int skipped) {
        var fields = PortainerBuildLogger.summaryFields();
        fields.put("files", Integer.toString(files));
        fields.put("created", Integer.toString(created));
        fields.put("skipped", Integer.toString(skipped));
        log.summaryWithDuration(startedNs, fields);
    }

    static String normalizeNamingStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return NAMING_CONTENT_HASH;
        }
        return raw.trim();
    }

    @Symbol({"portainerStackConfig", "portainerSwarmConfig"})
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Portainer Stack Config";
        }

        public String getPortainerConnectionSummary() {
            return PortainerConnections.connectionSummary();
        }

        @POST
        public FormValidation doCheckEndpointId(
                @QueryParameter String value,
                @QueryParameter String portainerConnectionMode,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return PortainerConnections.checkEndpointId(value, portainerConnectionMode);
        }

        @POST
        public FormValidation doCheckPortainerUrl(
                @QueryParameter String value,
                @QueryParameter String portainerConnectionMode,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            return PortainerConnections.checkPortainerUrl(value, portainerConnectionMode);
        }

        @POST
        public FormValidation doCheckRepositoryUrl(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Repository URL is required.");
            }
            try {
                GitRepositoryUrl.normalize(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckConfigPath(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Config path is required.");
            }
            try {
                SwarmConfigNaming.normalizeConfigPath(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckFileGlob(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckNamingStrategy(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank() || NAMING_CONTENT_HASH.equals(value.trim())) {
                return FormValidation.ok();
            }
            return FormValidation.error("Only contentHash naming is supported.");
        }

        @POST
        public ListBoxModel doFillGitCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String gitCredentialsId) {
            return PortainerCredentials.fillSecretOrUsernamePassword(item, gitCredentialsId);
        }

        @POST
        public ListBoxModel doFillPortainerCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String portainerCredentialsId) {
            return PortainerCredentials.fillSecretText(item, portainerCredentialsId);
        }
    }
}
