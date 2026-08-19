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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Freestyle / Pipeline build step: create or update a Portainer Compose/Swarm stack from Git
 * <strong>or</strong> inline YAML. Symbol: {@code portainerStack}.
 * <p>
 * Stack source: default {@link StackSource#REPOSITORY} (Git create / git redeploy); optional
 * {@link StackSource#YAML} (string create / {@code StackFileContent} update).
 * Portainer connection: default {@link ConnectionMode#INHERIT} from System
 * ({@link PortainerGlobalConfiguration}); optional {@link ConnectionMode#MANUAL} URL + API key.
 * Vault overlay: default Not connected ({@code none}); optional Inherit from HashiCorp Vault Plugin
 * System (soft dep); optional Manual
 * HTTP AppRole ({@code vaultUrl} + Username/Password); or {@link ConnectionMode#NONE} to disable
 * overlay. Path/mount apply when Inherit or Manual.
 */
public class PortainerStackBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(PortainerStackBuilder.class.getName());

    public static final String TYPE_COMPOSE = "compose";
    public static final String TYPE_SWARM = "swarm";
    public static final String DEFAULT_COMPOSE_FILE = "docker-compose.yml";
    /** Default Git ref sent to Portainer when the field is empty. */
    public static final String DEFAULT_REPOSITORY_REFERENCE = "refs/heads/main";

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;
    /** Vault only: no overlay ({@link ConnectionMode#NONE}). */
    public static final String MODE_NONE = ConnectionMode.NONE;

    public static final String SOURCE_REPOSITORY = StackSource.REPOSITORY;
    public static final String SOURCE_YAML = StackSource.YAML;

    private final String endpointId;
    private final String stackType;
    private final String stackName;

    /** Git repository URL; required in repository mode. */
    private String repositoryUrl = "";

    /**
     * {@link StackSource#REPOSITORY} (default) or {@link StackSource#YAML}. Null in old configs
     * → repository.
     */
    private String stackSource;
    /** Inline compose/stack YAML when {@link #SOURCE_YAML}. Never logged in full. */
    private String stackFileContent;

    private String composeFilePath = DEFAULT_COMPOSE_FILE;
    private String repositoryReferenceName;
    private String gitCredentialsId;
    private String env;
    private boolean prune;
    private boolean repullImageAndRedeploy;

    /**
     * When true, on update/redeploy load existing stack {@code Env[]} from Portainer and
     * overlay step {@code env} (then Vault). Default false: step+Vault replace Env wholesale.
     * {@code null} (legacy configs) means false.
     */
    private Boolean mergeEnvWithExisting;

    /** {@link ConnectionMode#INHERIT} (default) or {@link ConnectionMode#MANUAL}. */
    private String portainerConnectionMode;
    /** Manual Portainer URL (ignored when Inherit). */
    private String portainerUrl;
    /** Manual Portainer API key Secret text credentials id (ignored when Inherit). */
    private String portainerCredentialsId;

    /**
     * Vault connection: {@link #MODE_NONE} (default), Inherit, or Manual. Null in old configs —
     * see {@link #getVaultConnectionMode()} migration.
     */
    private String vaultConnectionMode;
    /** Manual Vault URL (ignored when Inherit). */
    private String vaultUrl;
    /** Manual Username/Password credential: username = role_id, password = secret_id. */
    private String vaultAppRoleCredentialsId;
    private String vaultPath;
    private String vaultMount;
    private String vaultNamespace;
    private String vaultVersion;

    /**
     * When true, also write DEBUG HTTP/timing lines to the build console.
     * Default false — DEBUG stays on JUL FINE only.
     */
    private boolean verboseLogging;

    /**
     * When true, run connection resolve + preflight (+ Manual YAML checks) and log what would be
     * deployed, without create/redeploy/update or Vault overlay.
     */
    private boolean validateOnly;

    @DataBoundConstructor
    public PortainerStackBuilder(String endpointId, String stackType, String stackName) {
        this.endpointId = endpointId == null ? "" : endpointId.trim();
        this.stackType =
                stackType == null || stackType.isBlank() ? TYPE_COMPOSE : stackType.trim();
        this.stackName = stackName == null ? "" : stackName.trim();
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getStackType() {
        return stackType;
    }

    public String getStackName() {
        return stackName;
    }

    /**
     * Stack source mode. Default {@link #SOURCE_REPOSITORY} for migration (configs without the field).
     */
    public String getStackSource() {
        return StackSource.normalize(stackSource);
    }

    /**
     * Pipeline / XStream / Freestyle string ({@code repository}|{@code yaml}).
     */
    @DataBoundSetter
    public void setStackSource(String stackSource) {
        this.stackSource = StackSource.normalize(stackSource);
    }

    public String getStackFileContent() {
        return stackFileContent;
    }

    @DataBoundSetter
    public void setStackFileContent(String stackFileContent) {
        this.stackFileContent =
                stackFileContent == null || stackFileContent.isBlank() ? null : stackFileContent;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    @DataBoundSetter
    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl == null ? "" : repositoryUrl.trim();
    }

    public String getComposeFilePath() {
        return composeFilePath;
    }

    @DataBoundSetter
    public void setComposeFilePath(String composeFilePath) {
        if (composeFilePath == null || composeFilePath.isBlank()) {
            this.composeFilePath = DEFAULT_COMPOSE_FILE;
        } else {
            this.composeFilePath = composeFilePath.trim();
        }
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

    public String getEnv() {
        return env;
    }

    @DataBoundSetter
    public void setEnv(String env) {
        this.env = env == null || env.isBlank() ? null : env;
    }

    public boolean isPrune() {
        return prune;
    }

    @DataBoundSetter
    public void setPrune(boolean prune) {
        this.prune = prune;
    }

    public boolean isRepullImageAndRedeploy() {
        return repullImageAndRedeploy;
    }

    @DataBoundSetter
    public void setRepullImageAndRedeploy(boolean repullImageAndRedeploy) {
        this.repullImageAndRedeploy = repullImageAndRedeploy;
    }

    public boolean isMergeEnvWithExisting() {
        return mergeEnvWithExisting != null && mergeEnvWithExisting;
    }

    @DataBoundSetter
    public void setMergeEnvWithExisting(boolean mergeEnvWithExisting) {
        this.mergeEnvWithExisting = mergeEnvWithExisting;
    }

    public String getPortainerConnectionMode() {
        String mode = ConnectionMode.normalize(portainerConnectionMode, MODE_INHERIT);
        return ConnectionMode.isNone(mode) ? MODE_INHERIT : mode;
    }

    /**
     * Pipeline / XStream / Freestyle string mode ({@code inherit}|{@code manual}). Unknown values
     * (including Vault {@code none}) fall back to Inherit for Portainer.
     */
    @DataBoundSetter
    public void setPortainerConnectionMode(String portainerConnectionMode) {
        String normalized = ConnectionMode.normalize(portainerConnectionMode, MODE_INHERIT);
        // Portainer has no Off mode — map none/aliases to Inherit.
        this.portainerConnectionMode = ConnectionMode.isNone(normalized) ? MODE_INHERIT : normalized;
    }

    public String getPortainerUrl() {
        return portainerUrl;
    }

    @DataBoundSetter
    public void setPortainerUrl(String portainerUrl) {
        this.portainerUrl = blankToNull(portainerUrl);
    }

    public String getPortainerCredentialsId() {
        return portainerCredentialsId;
    }

    @DataBoundSetter
    public void setPortainerCredentialsId(String portainerCredentialsId) {
        this.portainerCredentialsId = blankToNull(portainerCredentialsId);
    }

    /**
     * Vault connection mode. Default {@link #MODE_NONE} (Not connected).
     * Jobs that only have {@code vaultUrl} / AppRole credentials (no mode field) resolve to
     * {@link #MODE_MANUAL}. Jobs that only have {@code vaultPath} (no mode field) resolve to
     * {@link #MODE_INHERIT}. Explicit {@link #MODE_NONE} disables overlay (preferred over empty path).
     */
    public String getVaultConnectionMode() {
        if (vaultConnectionMode != null && !vaultConnectionMode.isBlank()) {
            return ConnectionMode.normalize(vaultConnectionMode, MODE_NONE);
        }
        if (nonBlank(vaultUrl) || nonBlank(vaultAppRoleCredentialsId)) {
            return MODE_MANUAL;
        }
        // Path-only configs without a mode field stay on Inherit.
        if (nonBlank(vaultPath)) {
            return MODE_INHERIT;
        }
        return MODE_NONE;
    }

    /**
     * Pipeline / XStream / Freestyle string mode ({@code inherit}|{@code manual}|{@code none}).
     */
    @DataBoundSetter
    public void setVaultConnectionMode(String vaultConnectionMode) {
        this.vaultConnectionMode = ConnectionMode.normalize(vaultConnectionMode, MODE_NONE);
    }

    public String getVaultUrl() {
        return vaultUrl;
    }

    @DataBoundSetter
    public void setVaultUrl(String vaultUrl) {
        this.vaultUrl = blankToNull(vaultUrl);
    }

    public String getVaultAppRoleCredentialsId() {
        return vaultAppRoleCredentialsId;
    }

    @DataBoundSetter
    public void setVaultAppRoleCredentialsId(String vaultAppRoleCredentialsId) {
        this.vaultAppRoleCredentialsId = blankToNull(vaultAppRoleCredentialsId);
    }

    public String getVaultPath() {
        return vaultPath;
    }

    @DataBoundSetter
    public void setVaultPath(String vaultPath) {
        this.vaultPath = blankToNull(vaultPath);
    }

    public String getVaultMount() {
        return vaultMount;
    }

    @DataBoundSetter
    public void setVaultMount(String vaultMount) {
        this.vaultMount = blankToNull(vaultMount);
    }

    public String getVaultNamespace() {
        return vaultNamespace;
    }

    @DataBoundSetter
    public void setVaultNamespace(String vaultNamespace) {
        this.vaultNamespace = blankToNull(vaultNamespace);
    }

    public String getVaultVersion() {
        return vaultVersion;
    }

    @DataBoundSetter
    public void setVaultVersion(String vaultVersion) {
        this.vaultVersion = blankToNull(vaultVersion);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
            log.open(PortainerBuildLogger.TITLE_STACK);
            performBody(run, buildEnv, log);
        }
    }

    private void performBody(
            Run<?, ?> run,
            EnvVars buildEnv,
            PortainerBuildLogger log) throws IOException {
        long startedNs = System.nanoTime();
        StackParsedInputs inputs = parseStackInputs(buildEnv, log);
        List<PortainerClient.EnvPair> envPairs = parseExpandedEnv(buildEnv, log);

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

        PortainerCredentials.GitAuth gitAuth = null;
        if (!inputs.yamlMode) {
            gitAuth = PortainerConnections.resolveOptionalGitAuth(gitCredentialsId, item, log);
        }

        String vaultModeLabel = vaultModeLabelForLog(buildEnv);
        VaultFields vaultFields = resolveVaultFieldsIfNeeded(vaultModeLabel, buildEnv, log);

        logStackPlan(log, connection, inputs);

        try (PortainerClient client = new PortainerClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            PortainerConnections.runPreflight(client, connection, apiKey, inputs.endpoint, false, log);
            if (!inputs.yamlMode) {
                PortainerBuildLogger.logGitPreflight(
                        log, inputs.gitRef, inputs.repoUrl, null, null, inputs.compose, null);
            }
            runVaultPreflight(connection, vaultFields, run, buildEnv, item, log);

            int existingId = resolveExistingStackId(client, connection, apiKey, inputs.endpoint, log);

            if (validateOnly) {
                finishValidateOnly(log, startedNs, inputs, existingId, envPairs, vaultModeLabel);
                return;
            }

            deployAndSummarize(
                    client,
                    connection,
                    apiKey,
                    new StackDeployRequest(
                            inputs,
                            existingId,
                            new StackEnvAndAuth(envPairs, gitAuth),
                            new StackRunContext(run, buildEnv, item, vaultFields)),
                    log,
                    startedNs);
        }
    }

    private StackParsedInputs parseStackInputs(EnvVars buildEnv, PortainerBuildLogger log)
            throws AbortException {
        final String type = PortainerConnections.abortOn(log, () -> {
            String t = normalizeStackType(stackType);
            PortainerStackName.requireValid(stackName);
            return t;
        });
        final int endpoint = PortainerConnections.abortOn(
                log, () -> PortainerConnections.resolveEndpointId(endpointId, buildEnv));
        if (StackSource.isYaml(getStackSource())) {
            return parseYamlInputs(log, type, endpoint);
        }
        return parseGitInputs(buildEnv, log, type, endpoint);
    }

    private StackParsedInputs parseYamlInputs(PortainerBuildLogger log, String type, int endpoint)
            throws AbortException {
        String yamlContent = stackFileContent;
        if (yamlContent == null || yamlContent.isBlank()) {
            throw PortainerConnections.abort(log, "Stack YAML content is required for Manual YAML source.");
        }
        final String validated = yamlContent;
        PortainerConnections.abortOn(log, () -> {
            ComposeYamlValidator.requireValid(validated);
            return null;
        });
        return StackParsedInputs.yaml(type, endpoint, validated);
    }

    private StackParsedInputs parseGitInputs(
            EnvVars buildEnv, PortainerBuildLogger log, String type, int endpoint)
            throws AbortException {
        String repoUrl = PortainerConnections.abortOn(log, () -> GitRepositoryUrl.normalize(repositoryUrl));
        String gitRef = resolveRepositoryReference(repositoryReferenceName, buildEnv);
        String compose = PortainerConnections.abortOn(log, () -> PortainerComposePath.normalize(
                composeFilePath == null || composeFilePath.isBlank()
                        ? DEFAULT_COMPOSE_FILE
                        : buildEnv.expand(composeFilePath)));
        return StackParsedInputs.git(type, endpoint, repoUrl, gitRef, compose);
    }

    private List<PortainerClient.EnvPair> parseExpandedEnv(EnvVars buildEnv, PortainerBuildLogger log)
            throws AbortException {
        return PortainerConnections.abortOn(log, () -> {
            List<PortainerClient.EnvPair> parsed = PortainerEnvParser.parse(env);
            List<PortainerClient.EnvPair> expanded = new ArrayList<>(parsed.size());
            for (PortainerClient.EnvPair pair : parsed) {
                String value = pair.value == null ? "" : buildEnv.expand(pair.value);
                expanded.add(new PortainerClient.EnvPair(pair.name, value));
            }
            return expanded;
        });
    }

    private VaultFields resolveVaultFieldsIfNeeded(
            String vaultModeLabel, EnvVars buildEnv, PortainerBuildLogger log) throws AbortException {
        if ("off".equals(vaultModeLabel)) {
            return null;
        }
        return PortainerConnections.abortOn(
                log,
                () -> VaultFields.parse(
                        vaultPath, vaultMount, vaultVersion, vaultNamespace, vaultUrl, buildEnv));
    }

    private void logStackPlan(
            PortainerBuildLogger log, ResolvedConnection connection, StackParsedInputs inputs) {
        PortainerBuildLogger.debugPortainerStart(
                log,
                connection,
                inputs.endpoint,
                "prune=" + prune + " pullImage=" + repullImageAndRedeploy);
        log.info("Stack name=" + stackName + " type=" + inputs.type);
        if (inputs.yamlMode) {
            log.debug("yamlLength=" + inputs.yamlContent.length()
                    + " yamlHash=" + PortainerConnections.shortContentHash(inputs.yamlContent));
        }
    }

    private void runVaultPreflight(
            ResolvedConnection connection,
            VaultFields vaultFields,
            Run<?, ?> run,
            EnvVars buildEnv,
            Item item,
            PortainerBuildLogger log) throws AbortException {
        if (vaultFields != null && nonBlank(vaultFields.pathRaw)) {
            log.debug(PortainerBuildLogger.formatVaultConnection(
                    getVaultConnectionMode(),
                    vaultFields.pathRaw,
                    vaultFields.mount,
                    vaultFields.version));
        }
        VaultConnections.runPreflight(new VaultConnections.Request(
                getVaultConnectionMode(),
                false,
                vaultUrl,
                vaultAppRoleCredentialsId,
                vaultPath,
                vaultNamespace,
                run,
                buildEnv,
                item,
                connection.connectTimeoutMs,
                connection.readTimeoutMs,
                log));
    }

    private int resolveExistingStackId(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            PortainerBuildLogger log) throws AbortException {
        log.info("Ensuring stack");
        final int existingId;
        try {
            existingId = client.findStackIdByName(connection.baseUrl, apiKey, stackName, endpoint);
        } catch (IOException e) {
            throw PortainerConnections.abort(
                    log, "Stack lookup failed: " + PortainerConnections.truncateMessage(e), e);
        }
        if (existingId >= 0) {
            log.debug("Stack id=" + existingId + " already exists");
        } else {
            log.debug("Stack not found by name");
        }
        return existingId;
    }

    private void finishValidateOnly(
            PortainerBuildLogger log,
            long startedNs,
            StackParsedInputs inputs,
            int existingId,
            List<PortainerClient.EnvPair> envPairs,
            String vaultModeLabel) {
        log.info("Validate-only — skipping deploy");
        log.debug("Would "
                + (existingId >= 0 ? "update" : "create")
                + " stack name=" + stackName
                + " type=" + inputs.type
                + " source=" + (inputs.yamlMode ? "yaml" : "git")
                + " envKeys=" + envPairs.size()
                + " vault=" + vaultModeLabel);
        summarize(log, startedNs, "validated", existingId);
    }

    private void deployAndSummarize(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            StackDeployRequest req,
            PortainerBuildLogger log,
            long startedNs) throws AbortException {
        try {
            MergedEnv merged = mergeEnvWithExisting(
                    client, connection, apiKey, req.existingId, req.envAndAuth.envPairs, log);
            StackRunContext ctx = req.runContext;
            Map<String, String> vaultOverlay = resolveVaultOverlay(
                    ctx.run, ctx.buildEnv, ctx.item, connection, ctx.vaultFields, log);
            int vaultKeyCount = vaultOverlay.size();
            List<PortainerClient.EnvPair> finalEnv = PortainerEnvMerge.merge(merged.envPairs, vaultOverlay);
            logEnvKeys(log, finalEnv, merged.existingKeyCount, merged.stepKeyCount, vaultKeyCount);

            final DeployOutcome deploy;
            if (req.inputs.yamlMode) {
                deploy = deployFromYaml(
                        client,
                        connection,
                        apiKey,
                        req.inputs.endpoint,
                        req.inputs.type,
                        req.existingId,
                        new StackYamlDeployParams(req.inputs.yamlContent, finalEnv));
            } else {
                deploy = deployFromGit(
                        client,
                        connection,
                        apiKey,
                        req.inputs.endpoint,
                        req.inputs.type,
                        req.existingId,
                        new StackGitDeployParams(
                                req.inputs.repoUrl,
                                req.inputs.compose,
                                req.inputs.gitRef,
                                req.envAndAuth.gitAuth,
                                finalEnv));
            }
            summarize(log, startedNs, deploy.outcome, deploy.stackId);
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            String summary = "Stack operation failed: " + PortainerConnections.truncateMessage(e);
            throw PortainerConnections.abort(log, summary, e);
        }
    }

    private MergedEnv mergeEnvWithExisting(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int existingId,
            List<PortainerClient.EnvPair> envPairs,
            PortainerBuildLogger log) throws AbortException {
        int stepKeyCount = envPairs == null ? 0 : envPairs.size();
        if (!isMergeEnvWithExisting() || existingId < 0) {
            return new MergedEnv(envPairs, 0, stepKeyCount);
        }
        try {
            List<PortainerClient.EnvPair> existingEnv =
                    client.getStackEnv(connection.baseUrl, apiKey, existingId);
            int existingKeyCount = existingEnv.size();
            List<PortainerClient.EnvPair> overlaid = PortainerEnvMerge.overlay(existingEnv, envPairs);
            log.debug("Merged existing stack env keys=" + existingKeyCount
                    + " with step keys=" + stepKeyCount);
            return new MergedEnv(overlaid, existingKeyCount, stepKeyCount);
        } catch (IOException e) {
            throw PortainerConnections.abort(
                    log, "Load stack env failed: " + PortainerConnections.truncateMessage(e), e);
        }
    }

    private void summarize(
            PortainerBuildLogger log,
            long startedNs,
            String outcome,
            int stackId) {
        var fields = PortainerBuildLogger.summaryFields();
        if (outcome != null && !outcome.isBlank()) {
            fields.put("outcome", outcome);
        }
        if (stackId >= 0) {
            fields.put("stackId", Integer.toString(stackId));
        }
        log.summaryWithDuration(startedNs, fields);
    }

    private DeployOutcome deployFromGit(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            String type,
            int existingId,
            StackGitDeployParams params) throws IOException {
        if (existingId < 0) {
            PortainerClient.StackFromGitRequest req = new PortainerClient.StackFromGitRequest(
                    stackName,
                    params.repoUrl,
                    params.compose,
                    params.gitRef,
                    params.gitAuth == null ? null : params.gitAuth.username,
                    params.gitAuth == null ? null : params.gitAuth.password,
                    params.envPairs);
            JsonNode created;
            if (TYPE_SWARM.equals(type)) {
                created = client.createSwarmStackFromRepository(
                        connection.baseUrl, apiKey, endpoint, req);
            } else {
                created = client.createStandaloneStackFromRepository(
                        connection.baseUrl, apiKey, endpoint, req);
            }
            int id = created.path("Id").asInt(created.path("ID").asInt(-1));
            return new DeployOutcome("created", id);
        }
        PortainerClient.GitRedeployRequest req = new PortainerClient.GitRedeployRequest(
                params.envPairs,
                prune,
                repullImageAndRedeploy,
                params.gitRef,
                params.gitAuth == null ? null : params.gitAuth.username,
                params.gitAuth == null ? null : params.gitAuth.password);
        client.gitRedeploy(connection.baseUrl, apiKey, existingId, endpoint, req);
        return new DeployOutcome("updated", existingId);
    }

    private DeployOutcome deployFromYaml(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            String type,
            int existingId,
            StackYamlDeployParams params) throws IOException {
        if (existingId < 0) {
            PortainerClient.StackFromStringRequest req =
                    new PortainerClient.StackFromStringRequest(
                            stackName, params.yamlContent, params.envPairs);
            JsonNode created;
            if (TYPE_SWARM.equals(type)) {
                created = client.createSwarmStackFromString(
                        connection.baseUrl, apiKey, endpoint, req);
            } else {
                created = client.createStandaloneStackFromString(
                        connection.baseUrl, apiKey, endpoint, req);
            }
            int id = created.path("Id").asInt(created.path("ID").asInt(-1));
            return new DeployOutcome("created", id);
        }
        PortainerClient.StackFileUpdateRequest req = new PortainerClient.StackFileUpdateRequest(
                params.yamlContent, params.envPairs, prune, repullImageAndRedeploy);
        client.updateStackFileContent(connection.baseUrl, apiKey, existingId, endpoint, req);
        return new DeployOutcome("updated", existingId);
    }

    /**
     * INFO count of merged env keys; DEBUG names and source counts (never values).
     */
    static void logEnvKeys(
            PortainerBuildLogger log,
            List<PortainerClient.EnvPair> envPairs,
            int existing,
            int step,
            int vault) {
        int n = envPairs == null ? 0 : envPairs.size();
        log.info("Env keys=" + n);
        if (n > 0) {
            List<String> names = new java.util.ArrayList<>();
            for (PortainerClient.EnvPair pair : envPairs) {
                if (pair == null || pair.name == null || pair.name.isBlank()) {
                    continue;
                }
                names.add(pair.name.trim());
            }
            log.debug("Env keys: " + PortainerBuildLogger.formatNameList(names));
        }
        log.debug("Env sources: existing=" + Math.max(0, existing)
                + " step=" + Math.max(0, step)
                + " vault=" + Math.max(0, vault));
    }

    /**
     * Optional Vault KV v2 overlay. Never {@code null}: empty map when Vault is off
     * ({@link #MODE_NONE}) or when Inherit/Manual have an empty path (soft-skip).
     * Never logs secret values, role_id, secret_id, or tokens.
     */
    Map<String, String> resolveVaultOverlay(
            Run<?, ?> run,
            EnvVars buildEnv,
            Item item,
            ResolvedConnection connection,
            VaultFields fields,
            PortainerBuildLogger log) throws AbortException {
        int connectMs = connection == null
                ? PortainerGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS
                : connection.connectTimeoutMs;
        int readMs = connection == null
                ? PortainerGlobalConfiguration.DEFAULT_READ_TIMEOUT_MS
                : connection.readTimeoutMs;
        return VaultKv.resolve(new VaultKv.Request(
                new VaultKv.Request.VaultSpec(
                        VaultKv.Policy.OPTIONAL_SOFT_SKIP,
                        getVaultConnectionMode(),
                        fields,
                        vaultAppRoleCredentialsId),
                new VaultKv.Request.RunContext(run, buildEnv, item),
                new VaultKv.Request.Timeouts(connectMs, readMs),
                log));
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Safe config label: {@code off} when Not connected or path empty; else inherit|manual. */
    String vaultModeLabelForLog(EnvVars buildEnv) {
        if (ConnectionMode.isNone(getVaultConnectionMode())) {
            return "off";
        }
        return nonBlank(VaultFields.expandOptional(vaultPath, buildEnv))
                ? getVaultConnectionMode()
                : "off";
    }

    /**
     * Resolves Git ref for Portainer: expands {@code $VAR}/{@code ${VAR}} from the build environment,
     * then falls back to {@link #DEFAULT_REPOSITORY_REFERENCE} when empty.
     */
    static String resolveRepositoryReference(String configured, EnvVars buildEnv) {
        String raw = configured == null || configured.isBlank()
                ? DEFAULT_REPOSITORY_REFERENCE
                : configured.trim();
        String expanded = buildEnv == null ? raw : buildEnv.expand(raw);
        if (expanded == null || expanded.isBlank()) {
            return DEFAULT_REPOSITORY_REFERENCE;
        }
        return expanded.trim();
    }

    static String normalizeStackType(String raw) throws AbortException {
        if (raw == null || raw.isBlank()) {
            return TYPE_COMPOSE;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if ("standalone".equals(t)) {
            t = TYPE_COMPOSE;
        }
        if (!TYPE_COMPOSE.equals(t) && !TYPE_SWARM.equals(t)) {
            throw new AbortException("Stack type must be 'compose' or 'swarm' (got '" + raw.trim() + "').");
        }
        return t;
    }

    private static final class StackParsedInputs {
        final String type;
        final int endpoint;
        final boolean yamlMode;
        final String yamlContent;
        final String repoUrl;
        final String gitRef;
        final String compose;

        private StackParsedInputs(
                String type,
                int endpoint,
                boolean yamlMode,
                String yamlContent,
                String repoUrl,
                String gitRef,
                String compose) {
            this.type = type;
            this.endpoint = endpoint;
            this.yamlMode = yamlMode;
            this.yamlContent = yamlContent;
            this.repoUrl = repoUrl;
            this.gitRef = gitRef;
            this.compose = compose;
        }

        static StackParsedInputs yaml(String type, int endpoint, String yamlContent) {
            return new StackParsedInputs(type, endpoint, true, yamlContent, null, null, null);
        }

        static StackParsedInputs git(
                String type, int endpoint, String repoUrl, String gitRef, String compose) {
            return new StackParsedInputs(type, endpoint, false, null, repoUrl, gitRef, compose);
        }
    }

    private static final class StackGitDeployParams {
        final String repoUrl;
        final String compose;
        final String gitRef;
        final PortainerCredentials.GitAuth gitAuth;
        final List<PortainerClient.EnvPair> envPairs;

        StackGitDeployParams(
                String repoUrl,
                String compose,
                String gitRef,
                PortainerCredentials.GitAuth gitAuth,
                List<PortainerClient.EnvPair> envPairs) {
            this.repoUrl = repoUrl;
            this.compose = compose;
            this.gitRef = gitRef;
            this.gitAuth = gitAuth;
            this.envPairs = envPairs;
        }
    }

    private static final class StackYamlDeployParams {
        final String yamlContent;
        final List<PortainerClient.EnvPair> envPairs;

        StackYamlDeployParams(String yamlContent, List<PortainerClient.EnvPair> envPairs) {
            this.yamlContent = yamlContent;
            this.envPairs = envPairs;
        }
    }

    private static final class StackEnvAndAuth {
        final List<PortainerClient.EnvPair> envPairs;
        final PortainerCredentials.GitAuth gitAuth;

        StackEnvAndAuth(List<PortainerClient.EnvPair> envPairs, PortainerCredentials.GitAuth gitAuth) {
            this.envPairs = envPairs;
            this.gitAuth = gitAuth;
        }
    }

    private static final class StackRunContext {
        final Run<?, ?> run;
        final EnvVars buildEnv;
        final Item item;
        final VaultFields vaultFields;

        StackRunContext(Run<?, ?> run, EnvVars buildEnv, Item item, VaultFields vaultFields) {
            this.run = run;
            this.buildEnv = buildEnv;
            this.item = item;
            this.vaultFields = vaultFields;
        }
    }

    private static final class StackDeployRequest {
        final StackParsedInputs inputs;
        final int existingId;
        final StackEnvAndAuth envAndAuth;
        final StackRunContext runContext;

        StackDeployRequest(
                StackParsedInputs inputs,
                int existingId,
                StackEnvAndAuth envAndAuth,
                StackRunContext runContext) {
            this.inputs = inputs;
            this.existingId = existingId;
            this.envAndAuth = envAndAuth;
            this.runContext = runContext;
        }
    }

    private static final class MergedEnv {
        final List<PortainerClient.EnvPair> envPairs;
        final int existingKeyCount;
        final int stepKeyCount;

        MergedEnv(List<PortainerClient.EnvPair> envPairs, int existingKeyCount, int stepKeyCount) {
            this.envPairs = envPairs;
            this.existingKeyCount = existingKeyCount;
            this.stepKeyCount = stepKeyCount;
        }
    }

    @Symbol("portainerStack")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /** Read-only summary when Vault connection is Not connected. */
        public static final String VAULT_NONE_SUMMARY = "Vault disabled.";

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Portainer Stack Deployment";
        }

        /**
         * Read-only summary for the job config form (Inherit readiness).
         */
        public String getPortainerConnectionSummary() {
            return PortainerConnections.connectionSummary();
        }

        public boolean isVaultPluginPresent() {
            return VaultPluginInherit.isPluginPresent();
        }

        /** True when Inherit summary shows the muted path/mount hint. */
        public boolean isVaultInheritReady() {
            return VaultPluginInherit.isPluginPresent() && VaultPluginInherit.isSystemConfigured();
        }

        public String getVaultInheritSummary() {
            return VaultPluginInherit.inheritSummary();
        }

        @POST
        public FormValidation doCheckStackType(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                // Select may be visually Compose while value is still empty until first interaction.
                return FormValidation.ok();
            }
            return checkEnum(value, "Stack type", TYPE_COMPOSE, TYPE_SWARM);
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
            String err = PortainerStackName.validate(value);
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
        public FormValidation doCheckStackFileContent(
                @QueryParameter String value,
                @QueryParameter String stackSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (!StackSource.isYaml(stackSource)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Stack YAML content is required for Manual YAML.");
            }
            String err = ComposeYamlValidator.validate(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckComposeFilePath(
                @QueryParameter String value,
                @QueryParameter String stackSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (StackSource.isYaml(stackSource)) {
                return FormValidation.ok();
            }
            String err = PortainerComposePath.validate(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckEnv(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            try {
                PortainerEnvParser.parse(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckVaultUrl(
                @QueryParameter String value,
                @QueryParameter String vaultConnectionMode,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (!ConnectionMode.isManual(vaultConnectionMode)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                VaultUrl.normalizeBaseUrlSyntaxOnly(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckVaultPath(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                VaultClient.normalizeSecretPath(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckVaultMount(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                VaultClient.normalizeMount(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckVaultVersion(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                VaultClient.parseVersion(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillStackTypeItems(@AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            ListBoxModel m = new ListBoxModel();
            m.add("Compose (standalone)", TYPE_COMPOSE);
            m.add("Swarm", TYPE_SWARM);
            return m;
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

        @POST
        public ListBoxModel doFillVaultAppRoleCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String vaultAppRoleCredentialsId) {
            return PortainerCredentials.fillVaultAppRoleCredentials(item, vaultAppRoleCredentialsId);
        }

        private static FormValidation checkEnum(String value, String label, String... allowed) {
            if (value == null || value.isBlank()) {
                return FormValidation.error(label + " is required.");
            }
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (String a : allowed) {
                if (a.equals(v)) {
                    return FormValidation.ok();
                }
            }
            return FormValidation.error(label + " must be one of: " + String.join(", ", allowed) + ".");
        }
    }
}
