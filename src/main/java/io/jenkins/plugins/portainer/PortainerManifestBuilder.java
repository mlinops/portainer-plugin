package io.jenkins.plugins.portainer;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
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
import java.util.logging.Logger;

/**
 * Freestyle / Pipeline build step: apply a Kubernetes manifest via Portainer
 * ({@code @Symbol("portainerManifest")}).
 * <p>
 * Kubernetes namespace comes from the manifest. This step does not send {@code Namespace}
 * in the Portainer API body. {@code stackName} is Portainer stack metadata only.
 */
public class PortainerManifestBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(PortainerManifestBuilder.class.getName());
    private static final String MSG_STALE_BEFORE_UPDATE =
            "Portainer stack exists but no live Kubernetes resources were found. "
                    + "Remove the stale stack in Portainer and retry.";
    private static final String MSG_STALE_AFTER_APPLY =
            "Deploy finished but no live Kubernetes resources were found. "
                    + "Remove the stale stack in Portainer and retry.";

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;
    public static final String SOURCE_REPOSITORY = StackSource.REPOSITORY;
    public static final String SOURCE_YAML = StackSource.YAML;
    public static final String DEFAULT_MANIFEST_FILE = "manifest.yaml";
    public static final String DEFAULT_REPOSITORY_REFERENCE = PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE;

    private final String endpointId;
    private final String stackName;

    private String stackSource;
    private String repositoryUrl = "";
    private String manifestFilePath = DEFAULT_MANIFEST_FILE;
    private String repositoryReferenceName;
    private String gitCredentialsId;
    private String stackFileContent;

    private String portainerConnectionMode;
    private String portainerUrl;
    private String portainerCredentialsId;
    private boolean verboseLogging;
    private boolean validateOnly;

    @DataBoundConstructor
    public PortainerManifestBuilder(String endpointId, String stackName) {
        this.endpointId = endpointId == null ? "" : endpointId.trim();
        this.stackName = stackName == null ? "" : stackName.trim();
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getStackName() {
        return stackName;
    }

    public String getStackSource() {
        return StackSource.normalize(stackSource);
    }

    @DataBoundSetter
    public void setStackSource(String stackSource) {
        this.stackSource = StackSource.normalize(stackSource);
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    @DataBoundSetter
    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl == null ? "" : repositoryUrl.trim();
    }

    public String getManifestFilePath() {
        return manifestFilePath == null || manifestFilePath.isBlank()
                ? DEFAULT_MANIFEST_FILE
                : manifestFilePath.trim();
    }

    @DataBoundSetter
    public void setManifestFilePath(String manifestFilePath) {
        this.manifestFilePath = manifestFilePath == null || manifestFilePath.isBlank()
                ? DEFAULT_MANIFEST_FILE
                : manifestFilePath.trim();
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

    public String getStackFileContent() {
        return stackFileContent;
    }

    @DataBoundSetter
    public void setStackFileContent(String stackFileContent) {
        this.stackFileContent = stackFileContent;
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

    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        return PortainerSteps.performFreestyle(build, launcher, listener, this);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull EnvVars buildEnv,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        try (PortainerBuildLogger log = new PortainerBuildLogger(LOGGER, listener, verboseLogging)) {
            log.open(PortainerBuildLogger.TITLE_MANIFEST);
            performBody(run, buildEnv, log);
        }
    }

    private void performBody(Run<?, ?> run, EnvVars buildEnv, PortainerBuildLogger log)
            throws InterruptedException, IOException {
        long startedNs = System.nanoTime();
        ManifestParsedInputs inputs = parseManifestInputs(buildEnv, log);

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

        PortainerCredentials.GitAuth gitAuth = null;
        if (!inputs.yamlMode) {
            gitAuth = PortainerConnections.resolveOptionalGitAuth(gitCredentialsId, run.getParent(), log);
        }

        logManifestPlan(log, connection, inputs);

        try (PortainerClient client = new PortainerClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            PortainerConnections.runPreflight(
                    client, connection, apiKey, inputs.endpoint, true, true, log);

            if (!inputs.yamlMode) {
                PortainerBuildLogger.logGitPreflight(
                        log, inputs.gitRef, inputs.repoUrl, null, null, null, inputs.manifestPath);
            }

            if (validateOnly) {
                log.info("Validate-only — skipping deploy");
                summarize(log, startedNs, "validated", -1);
                return;
            }

            applyAndSummarize(client, connection, apiKey, inputs, gitAuth, log, startedNs);
        }
    }

    private ManifestParsedInputs parseManifestInputs(EnvVars buildEnv, PortainerBuildLogger log)
            throws AbortException {
        PortainerConnections.abortOn(log, () -> {
            PortainerStackName.requireValidOptional(stackName);
            return null;
        });
        final int endpoint = PortainerConnections.abortOn(
                log, () -> PortainerConnections.resolveEndpointId(endpointId, buildEnv));
        if (StackSource.isYaml(getStackSource())) {
            return parseYamlInputs(log, endpoint);
        }
        return parseGitInputs(buildEnv, log, endpoint);
    }

    private ManifestParsedInputs parseYamlInputs(PortainerBuildLogger log, int endpoint)
            throws AbortException {
        String yamlContent = stackFileContent;
        if (yamlContent == null || yamlContent.isBlank()) {
            throw PortainerConnections.abort(log, "Manifest YAML content is required for Manual YAML source.");
        }
        final String validated = yamlContent;
        PortainerConnections.abortOn(log, () -> {
            requireLooksLikeYaml(validated);
            return null;
        });
        return ManifestParsedInputs.yaml(endpoint, validated);
    }

    private ManifestParsedInputs parseGitInputs(EnvVars buildEnv, PortainerBuildLogger log, int endpoint)
            throws AbortException {
        String repoUrl = PortainerConnections.abortOn(log, () -> GitRepositoryUrl.normalize(repositoryUrl));
        String manifestPath = PortainerConnections.abortOn(log, () -> PortainerComposePath.normalize(
                manifestFilePath == null || manifestFilePath.isBlank()
                        ? DEFAULT_MANIFEST_FILE
                        : buildEnv.expand(manifestFilePath)));
        String gitRef = PortainerStackBuilder.resolveRepositoryReference(repositoryReferenceName, buildEnv);
        return ManifestParsedInputs.git(endpoint, repoUrl, gitRef, manifestPath);
    }

    private void logManifestPlan(
            PortainerBuildLogger log, ResolvedConnection connection, ManifestParsedInputs inputs) {
        PortainerBuildLogger.debugPortainerStart(log, connection, inputs.endpoint, null);
        log.info("Manifest name=" + stackName);
        if (inputs.yamlMode) {
            log.debug("yamlLength=" + inputs.yamlContent.length()
                    + " yamlHash=" + PortainerConnections.shortContentHash(inputs.yamlContent));
        }
    }

    private void applyAndSummarize(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            ManifestParsedInputs inputs,
            PortainerCredentials.GitAuth gitAuth,
            PortainerBuildLogger log,
            long startedNs) throws AbortException, IOException {
        try {
            int existingId = resolveExistingStackId(client, connection, apiKey, inputs.endpoint, log);
            if (existingId >= 0) {
                ManifestDeployVerifier.requireLiveResources(
                        client,
                        connection,
                        apiKey,
                        inputs.endpoint,
                        existingId,
                        stackName,
                        MSG_STALE_BEFORE_UPDATE);
            }

            final DeployOutcome deploy;
            if (inputs.yamlMode) {
                deploy = deployYaml(client, connection, apiKey, inputs.endpoint, existingId, inputs.yamlContent);
            } else {
                deploy = deployGit(
                        client,
                        connection,
                        apiKey,
                        inputs.endpoint,
                        existingId,
                        new ManifestGitDeployParams(inputs.repoUrl, inputs.manifestPath, inputs.gitRef, gitAuth));
            }

            ManifestDeployVerifier.requireLiveResources(
                    client,
                    connection,
                    apiKey,
                    inputs.endpoint,
                    deploy.stackId,
                    stackName,
                    MSG_STALE_AFTER_APPLY);

            summarize(log, startedNs, deploy.outcome, deploy.stackId);
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            throw PortainerConnections.abort(
                    log, "Manifest operation failed: " + PortainerConnections.truncateMessage(e), e);
        }
    }

    private void summarize(PortainerBuildLogger log, long startedNs, String outcome, int stackId) {
        var fields = PortainerBuildLogger.summaryFields();
        fields.put("outcome", outcome);
        if (stackId >= 0) {
            fields.put("stackId", Integer.toString(stackId));
        }
        log.summaryWithDuration(startedNs, fields);
    }

    private int resolveExistingStackId(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            PortainerBuildLogger log) throws IOException {
        if (stackName == null || stackName.isBlank()) {
            log.debug("Stack name is empty");
            return -1;
        }
        int existingId = client.findStackIdByName(connection.baseUrl, apiKey, stackName, endpoint);
        if (existingId >= 0) {
            log.debug("Stack id=" + existingId + " already exists");
        } else {
            log.debug("Stack not found by name");
        }
        return existingId;
    }

    private static int stackIdFromCreateResponse(JsonNode created) {
        if (created == null) {
            return -1;
        }
        return created.path("Id").asInt(created.path("ID").asInt(-1));
    }

    private int resolveCreatedStackId(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            JsonNode created) throws IOException {
        int fromResponse = stackIdFromCreateResponse(created);
        if (fromResponse >= 0) {
            return fromResponse;
        }
        if (stackName == null || stackName.isBlank()) {
            return -1;
        }
        return client.findStackIdByName(connection.baseUrl, apiKey, stackName, endpoint);
    }

    private DeployOutcome deployYaml(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            int existingId,
            String yamlContent) throws IOException {
        if (existingId < 0) {
            JsonNode created = client.createKubernetesStackFromString(
                    connection.baseUrl,
                    apiKey,
                    endpoint,
                    new PortainerClient.KubernetesFromStringRequest(stackName, yamlContent));
            return new DeployOutcome(
                    "created",
                    resolveCreatedStackId(client, connection, apiKey, endpoint, created));
        }
        client.updateKubernetesStackFileContent(
                connection.baseUrl,
                apiKey,
                existingId,
                endpoint,
                new PortainerClient.KubernetesFileUpdateRequest(yamlContent, stackName));
        return new DeployOutcome("updated", existingId);
    }

    private DeployOutcome deployGit(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            int existingId,
            ManifestGitDeployParams params) throws IOException {
        if (existingId < 0) {
            JsonNode created = client.createKubernetesStackFromRepository(
                    connection.baseUrl,
                    apiKey,
                    endpoint,
                    new PortainerClient.KubernetesFromGitRequest(
                            stackName,
                            params.repoUrl,
                            params.manifestPath,
                            params.gitRef,
                            params.gitAuth == null ? null : params.gitAuth.username,
                            params.gitAuth == null ? null : params.gitAuth.password));
            return new DeployOutcome(
                    "created",
                    resolveCreatedStackId(client, connection, apiKey, endpoint, created));
        }
        client.updateKubernetesStackGit(
                connection.baseUrl,
                apiKey,
                existingId,
                endpoint,
                new PortainerClient.KubernetesGitUpdateRequest(
                        params.gitRef,
                        params.gitAuth == null ? null : params.gitAuth.username,
                        params.gitAuth == null ? null : params.gitAuth.password));
        return new DeployOutcome("updated", existingId);
    }

    static void requireLooksLikeYaml(String content) {
        YamlLooksLike.require(
                content,
                "Manifest YAML content is required.",
                "Manifest content does not look like YAML (expected ':' or '---').");
    }

    private static final class ManifestParsedInputs {
        final int endpoint;
        final boolean yamlMode;
        final String yamlContent;
        final String repoUrl;
        final String gitRef;
        final String manifestPath;

        private ManifestParsedInputs(
                int endpoint,
                boolean yamlMode,
                String yamlContent,
                String repoUrl,
                String gitRef,
                String manifestPath) {
            this.endpoint = endpoint;
            this.yamlMode = yamlMode;
            this.yamlContent = yamlContent;
            this.repoUrl = repoUrl;
            this.gitRef = gitRef;
            this.manifestPath = manifestPath;
        }

        static ManifestParsedInputs yaml(int endpoint, String yamlContent) {
            return new ManifestParsedInputs(endpoint, true, yamlContent, null, null, null);
        }

        static ManifestParsedInputs git(int endpoint, String repoUrl, String gitRef, String manifestPath) {
            return new ManifestParsedInputs(endpoint, false, null, repoUrl, gitRef, manifestPath);
        }
    }

    private static final class ManifestGitDeployParams {
        final String repoUrl;
        final String manifestPath;
        final String gitRef;
        final PortainerCredentials.GitAuth gitAuth;

        ManifestGitDeployParams(
                String repoUrl,
                String manifestPath,
                String gitRef,
                PortainerCredentials.GitAuth gitAuth) {
            this.repoUrl = repoUrl;
            this.manifestPath = manifestPath;
            this.gitRef = gitRef;
            this.gitAuth = gitAuth;
        }
    }

    @Symbol("portainerManifest")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Portainer Manifest Deployment";
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
        public FormValidation doCheckStackName(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            String err = PortainerStackName.validateOptional(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckRepositoryUrl(
                @QueryParameter String value,
                @QueryParameter String stackSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (StackSource.isYaml(stackSource)) {
                return FormValidation.ok();
            }
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
        public FormValidation doCheckManifestFilePath(
                @QueryParameter String value,
                @QueryParameter String stackSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (StackSource.isYaml(stackSource)) {
                return FormValidation.ok();
            }
            String err = PortainerComposePath.validate(
                    value == null || value.isBlank() ? DEFAULT_MANIFEST_FILE : value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckStackFileContent(
                @QueryParameter String value,
                @QueryParameter String stackSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (!StackSource.isYaml(stackSource)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Manifest YAML content is required for Manual YAML.");
            }
            try {
                requireLooksLikeYaml(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
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
