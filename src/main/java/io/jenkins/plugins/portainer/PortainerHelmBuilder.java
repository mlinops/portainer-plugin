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
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Freestyle / Pipeline build step: install or upgrade a Helm chart via Portainer
 * ({@code @Symbol("portainerHelm")}).
 * <p>
 * Uses {@code POST /api/endpoints/{id}/kubernetes/helm}. Portainer's libhelm treats that
 * install as install-or-upgrade, so an existing release is upgraded by re-POSTing (default).
 * Optional {@code forceReinstall} uninstalls then installs. Requires a Kubernetes endpoint
 * (Type 5/6/7).
 * <p>
 * Values: {@link HelmValuesSource#NONE} (default / chart defaults), {@link HelmValuesSource#REPOSITORY}
 * (Jenkins shallow-clones a values file), or {@link HelmValuesSource#YAML} (inline). Portainer
 * accepts only string {@code values} — there is no Git values API in Portainer.
 */
public class PortainerHelmBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(PortainerHelmBuilder.class.getName());

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;
    public static final String DEFAULT_NAMESPACE = KubernetesNamespaces.DEFAULT;
    public static final String DEFAULT_VALUES_FILE = "values.yaml";
    public static final String VALUES_NONE = HelmValuesSource.NONE;
    public static final String VALUES_REPOSITORY = HelmValuesSource.REPOSITORY;
    public static final String VALUES_YAML = HelmValuesSource.YAML;

    private static final String VALUES_FILE_PATH_LABEL = "Values file path";
    private static final Pattern RELEASE_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private final String endpointId;
    private final String releaseName;
    private final String chart;
    private final String repo;

    private String namespace = DEFAULT_NAMESPACE;
    private String version;
    /** Values source: {@code none} (default), {@code repository}, or {@code yaml}. */
    private String valuesSource;
    private String values;
    private String valuesRepositoryUrl;
    private String valuesFilePath;
    private String valuesGitCredentialsId;
    private String valuesRepositoryReferenceName;
    private boolean atomic;
    private boolean forceReinstall;
    private String portainerConnectionMode;
    private String portainerUrl;
    private String portainerCredentialsId;
    private boolean verboseLogging;
    /** When true, preflight + field checks only — no list/install/uninstall / ensure-NS. */
    private boolean validateOnly;
    /**
     * When true, ensure the target Kubernetes namespace exists via Portainer before install
     * ({@code GET/POST /api/kubernetes/{id}/namespaces…}). Default true; skipped when {@code validateOnly}.
     */
    private boolean ensureNamespace = true;

    @DataBoundConstructor
    public PortainerHelmBuilder(String endpointId, String releaseName, String chart, String repo) {
        this.endpointId = endpointId == null ? "" : endpointId.trim();
        this.releaseName = releaseName == null ? "" : releaseName.trim();
        this.chart = chart == null ? "" : chart.trim();
        this.repo = repo == null ? "" : repo.trim();
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public String getChart() {
        return chart;
    }

    public String getRepo() {
        return repo;
    }

    public String getNamespace() {
        return namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace.trim();
    }

    @DataBoundSetter
    public void setNamespace(String namespace) {
        this.namespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace.trim();
    }

    public String getVersion() {
        return version;
    }

    @DataBoundSetter
    public void setVersion(String version) {
        this.version = version == null || version.isBlank() ? null : version.trim();
    }

    /**
     * Effective values source. Migrates legacy configs: non-blank {@code values} without
     * {@code valuesSource} → Manual YAML; blank → No source.
     */
    public String getValuesSource() {
        return HelmValuesSource.resolve(valuesSource, values);
    }

    @DataBoundSetter
    public void setValuesSource(String valuesSource) {
        this.valuesSource = valuesSource == null || valuesSource.isBlank()
                ? null
                : HelmValuesSource.normalize(valuesSource);
    }

    public String getValues() {
        return values;
    }

    @DataBoundSetter
    public void setValues(String values) {
        this.values = values;
    }

    public String getValuesRepositoryUrl() {
        return valuesRepositoryUrl;
    }

    @DataBoundSetter
    public void setValuesRepositoryUrl(String valuesRepositoryUrl) {
        this.valuesRepositoryUrl = valuesRepositoryUrl == null ? "" : valuesRepositoryUrl.trim();
    }

    public String getValuesFilePath() {
        return valuesFilePath == null || valuesFilePath.isBlank() ? DEFAULT_VALUES_FILE : valuesFilePath;
    }

    @DataBoundSetter
    public void setValuesFilePath(String valuesFilePath) {
        this.valuesFilePath = valuesFilePath == null || valuesFilePath.isBlank()
                ? DEFAULT_VALUES_FILE
                : valuesFilePath.trim();
    }

    public String getValuesGitCredentialsId() {
        return valuesGitCredentialsId;
    }

    @DataBoundSetter
    public void setValuesGitCredentialsId(String valuesGitCredentialsId) {
        this.valuesGitCredentialsId =
                valuesGitCredentialsId == null || valuesGitCredentialsId.isBlank()
                        ? null
                        : valuesGitCredentialsId.trim();
    }

    public String getValuesRepositoryReferenceName() {
        return valuesRepositoryReferenceName == null || valuesRepositoryReferenceName.isBlank()
                ? PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE
                : valuesRepositoryReferenceName;
    }

    @DataBoundSetter
    public void setValuesRepositoryReferenceName(String valuesRepositoryReferenceName) {
        this.valuesRepositoryReferenceName =
                valuesRepositoryReferenceName == null || valuesRepositoryReferenceName.isBlank()
                        ? PortainerStackBuilder.DEFAULT_REPOSITORY_REFERENCE
                        : valuesRepositoryReferenceName.trim();
    }

    public boolean isAtomic() {
        return atomic;
    }

    @DataBoundSetter
    public void setAtomic(boolean atomic) {
        this.atomic = atomic;
    }

    public boolean isForceReinstall() {
        return forceReinstall;
    }

    @DataBoundSetter
    public void setForceReinstall(boolean forceReinstall) {
        this.forceReinstall = forceReinstall;
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

    public boolean isEnsureNamespace() {
        return ensureNamespace;
    }

    @DataBoundSetter
    public void setEnsureNamespace(boolean ensureNamespace) {
        this.ensureNamespace = ensureNamespace;
    }

    /**
     * Workspace is required only when Values source is Git ({@link HelmValuesSource#REPOSITORY}).
     * {@code none} / {@code yaml} run on the controller (3-arg {@link #perform}).
     */
    @Override
    public boolean requiresWorkspace() {
        return HelmValuesSource.isRepository(getValuesSource());
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
        performHelm(run, buildEnv, null, null, listener);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        performHelm(run, run.getEnvironment(listener), workspace, launcher, listener);
    }

    private void performHelm(
            @NonNull Run<?, ?> run,
            @NonNull EnvVars buildEnv,
            FilePath workspace,
            Launcher launcher,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        try (PortainerBuildLogger log = new PortainerBuildLogger(LOGGER, listener, verboseLogging)) {
            log.open(PortainerBuildLogger.TITLE_HELM);
            performBody(run, buildEnv, workspace, launcher, listener, log);
        }
    }

    private void performBody(
            Run<?, ?> run,
            EnvVars buildEnv,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            PortainerBuildLogger log) throws InterruptedException, IOException {
        long startedNs = System.nanoTime();
        HelmParsedInputs inputs = parseHelmInputs(buildEnv, log);

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

        PortainerBuildLogger.debugPortainerStart(
                log, connection, inputs.endpoint, helmDebugExtras(inputs));

        try (PortainerClient client = new PortainerClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            PortainerConnections.runPreflight(
                    client, connection, apiKey, inputs.endpoint, true, true, log);

            ValuesRepoLocals valuesRepo = prepareValuesRepoLocals(inputs.mode, buildEnv, log);
            if (!HelmValuesSource.isNone(inputs.mode)) {
                log.info("Loading values");
            }
            final String valuesYaml;
            try {
                valuesYaml = resolveValues(new ResolveValuesRequest(
                                inputs.mode, buildEnv, item, workspace, launcher, listener, valuesRepo))
                        .content;
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw PortainerConnections.abort(log, e.getMessage());
            } catch (IOException e) {
                throw PortainerConnections.abort(log, PortainerConnections.truncateMessage(e), e, true);
            }
            logReleasePlan(log, inputs, valuesYaml);

            if (validateOnly) {
                finishValidateOnly(log, startedNs, inputs);
                return;
            }

            deployAndSummarize(client, connection, apiKey, inputs, valuesYaml, log, startedNs);
        }
    }

    private HelmParsedInputs parseHelmInputs(EnvVars buildEnv, PortainerBuildLogger log)
            throws AbortException {
        final String expandedRelease = PortainerConnections.abortOn(log, () -> {
            String name = buildEnv.expand(releaseName == null ? "" : releaseName).trim();
            requireValidReleaseName(name);
            return name;
        });
        final String expandedNamespace = PortainerConnections.abortOn(
                log, () -> KubernetesNamespaces.resolve(namespace, buildEnv));
        PortainerConnections.abortOn(log, () -> {
            if (chart == null || chart.isBlank()) {
                throw new IllegalArgumentException("Helm chart name is required.");
            }
            return null;
        });
        final int endpoint = PortainerConnections.abortOn(
                log, () -> PortainerConnections.resolveEndpointId(endpointId, buildEnv));
        final String mode = getValuesSource();
        final String chartRepo = PortainerConnections.abortOn(
                log, () -> ChartRepositoryUrl.normalize(buildEnv.expand(repo)));
        final String chartVersion = version == null || version.isBlank()
                ? null
                : buildEnv.expand(version).trim();
        final String expandedChart = buildEnv.expand(chart).trim();
        return new HelmParsedInputs(
                expandedRelease, expandedNamespace, endpoint, mode, chartRepo, chartVersion, expandedChart);
    }

    private String helmDebugExtras(HelmParsedInputs inputs) {
        return "chartRepo=" + inputs.chartRepo
                + (blank(inputs.chartVersion) ? "" : " version=" + inputs.chartVersion)
                + " valuesSource=" + inputs.mode
                + " atomic=" + atomic
                + " forceReinstall=" + forceReinstall;
    }

    private ValuesRepoLocals prepareValuesRepoLocals(
            String mode, EnvVars buildEnv, PortainerBuildLogger log) throws AbortException {
        if (!HelmValuesSource.isRepository(mode)) {
            return ValuesRepoLocals.none();
        }
        String valuesRepoUrl = PortainerConnections.abortOn(
                log, () -> GitRepositoryUrl.normalize(expandValuesRepositoryUrl(buildEnv)));
        String valuesGitRef = PortainerStackBuilder.resolveRepositoryReference(
                valuesRepositoryReferenceName, buildEnv);
        String valuesPath = PortainerConnections.abortOn(
                log,
                () -> PortainerComposePath.normalize(
                        expandValuesFilePath(buildEnv), VALUES_FILE_PATH_LABEL));
        PortainerBuildLogger.logGitPreflight(
                log, valuesGitRef, valuesRepoUrl, valuesPath, null, null, null);
        return new ValuesRepoLocals(valuesRepoUrl, valuesGitRef, valuesPath);
    }

    private void logReleasePlan(PortainerBuildLogger log, HelmParsedInputs inputs, String valuesYaml) {
        log.info("Release name=" + inputs.release
                + " chart=" + inputs.chart
                + " namespace=" + inputs.namespace);
        log.info("Values source=" + inputs.mode);
        String valuesDebug = valuesDebugLine(inputs.mode, valuesYaml);
        if (valuesDebug != null) {
            log.debug(valuesDebug);
        }
    }

    private void finishValidateOnly(PortainerBuildLogger log, long startedNs, HelmParsedInputs inputs) {
        log.info("Validate-only — skipping deploy");
        if (ensureNamespace) {
            log.debug("Would ensure namespace=" + inputs.namespace);
        }
        log.debug("Would "
                + (forceReinstall ? "force-reinstall" : "install-or-upgrade")
                + " helm release=" + inputs.release
                + " chart=" + inputs.chart
                + " namespace=" + inputs.namespace
                + (blank(inputs.chartVersion) ? "" : " version=" + inputs.chartVersion)
                + " valuesSource=" + inputs.mode);
        summarize(log, startedNs, "validated", inputs.release, inputs.chart, inputs.chartVersion);
    }

    private void deployAndSummarize(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            HelmParsedInputs inputs,
            String valuesYaml,
            PortainerBuildLogger log,
            long startedNs) throws AbortException, IOException {
        try {
            String outcome = deployHelm(
                    client,
                    connection,
                    apiKey,
                    inputs.endpoint,
                    new HelmDeployParams(
                            inputs.release,
                            inputs.chart,
                            inputs.chartRepo,
                            inputs.namespace,
                            inputs.chartVersion,
                            valuesYaml),
                    log);
            summarize(log, startedNs, outcome, inputs.release, inputs.chart, inputs.chartVersion);
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            String msg = PortainerConnections.truncateMessage(e);
            throw PortainerConnections.abort(
                    log,
                    "Helm operation failed: " + msg + PortainerClient.kubernetesConnectivityHint(msg),
                    e,
                    true);
        }
    }

    private String deployHelm(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            HelmDeployParams params,
            PortainerBuildLogger log) throws IOException {
        if (ensureNamespace) {
            KubernetesNamespaces.ensure(client, connection, apiKey, endpoint, params.namespace, log);
        }
        boolean exists = client.helmReleaseExists(
                connection.baseUrl, apiKey, endpoint, params.release, params.namespace);
        if (forceReinstall) {
            uninstallExistingRelease(client, connection, apiKey, endpoint, params, exists, log);
        }
        log.info("Ensuring Helm release");
        client.installHelmChart(
                connection.baseUrl,
                apiKey,
                endpoint,
                new PortainerClient.HelmInstallRequest(
                        params.release,
                        params.chartName,
                        params.chartRepo,
                        params.namespace,
                        params.chartVersion,
                        params.valuesYaml,
                        atomic));
        return exists ? "updated" : "created";
    }

    private static void uninstallExistingRelease(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            HelmDeployParams params,
            boolean exists,
            PortainerBuildLogger log) throws IOException {
        if (exists) {
            log.info("Helm force reinstall — uninstalling then installing release=" + params.release);
            client.uninstallHelmRelease(
                    connection.baseUrl, apiKey, endpoint, params.release, params.namespace);
            log.info("Helm release uninstalled name=" + params.release);
        } else {
            log.debug("Helm release name not found");
        }
    }

    private ResolvedValues resolveValues(ResolveValuesRequest request)
            throws IOException, InterruptedException {
        if (HelmValuesSource.isNone(request.mode)) {
            return ResolvedValues.none();
        }
        if (HelmValuesSource.isYaml(request.mode)) {
            return resolveYamlValues();
        }
        return resolveRepositoryValues(request);
    }

    private ResolvedValues resolveYamlValues() {
        if (values == null || values.isBlank()) {
            throw new IllegalArgumentException("Values YAML content is required for Manual YAML source.");
        }
        requireLooksLikeYaml(values);
        return ResolvedValues.yaml(values);
    }

    private ResolvedValues resolveRepositoryValues(ResolveValuesRequest request)
            throws IOException, InterruptedException {
        if (request.workspace == null || request.launcher == null) {
            throw new IllegalStateException(
                    "Helm Values from repository require an agent workspace (wrap the step in node { } / agent any).");
        }
        String repoUrl = resolveValuesRepoUrl(request.buildEnv, request.precomputed);
        String path = resolveValuesPath(request.buildEnv, request.precomputed);
        String gitRef = resolveValuesGitRef(request.buildEnv, request.precomputed);
        PortainerCredentials.GitAuth gitAuth =
                PortainerConnections.resolveOptionalGitAuth(valuesGitCredentialsId, request.item);
        String content = GitRepositoryFiles.readFile(
                repoUrl,
                gitRef,
                path,
                new GitRepositoryFiles.CloneContext(
                        gitAuth, request.workspace, request.launcher, request.listener));
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Values file from repository is empty: " + path);
        }
        requireLooksLikeYaml(content);
        return ResolvedValues.repository(content, repoUrl, gitRef, path);
    }

    private String resolveValuesRepoUrl(EnvVars buildEnv, ValuesRepoLocals precomputed) {
        if (precomputed.repoUrl != null) {
            return precomputed.repoUrl;
        }
        return GitRepositoryUrl.normalize(expandValuesRepositoryUrl(buildEnv));
    }

    private String resolveValuesPath(EnvVars buildEnv, ValuesRepoLocals precomputed) {
        if (precomputed.path != null) {
            return precomputed.path;
        }
        return PortainerComposePath.normalize(expandValuesFilePath(buildEnv), VALUES_FILE_PATH_LABEL);
    }

    private String resolveValuesGitRef(EnvVars buildEnv, ValuesRepoLocals precomputed) {
        if (precomputed.gitRef != null) {
            return precomputed.gitRef;
        }
        return PortainerStackBuilder.resolveRepositoryReference(valuesRepositoryReferenceName, buildEnv);
    }

    private String expandValuesRepositoryUrl(EnvVars buildEnv) {
        String raw = valuesRepositoryUrl == null ? "" : valuesRepositoryUrl;
        return buildEnv.expand(raw);
    }

    private String expandValuesFilePath(EnvVars buildEnv) {
        if (valuesFilePath == null || valuesFilePath.isBlank()) {
            return DEFAULT_VALUES_FILE;
        }
        return buildEnv.expand(valuesFilePath);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Values payload size/hash for DEBUG; {@code null} when none (no values). */
    private static String valuesDebugLine(String mode, String valuesYaml) {
        if (HelmValuesSource.isNone(mode)) {
            return null;
        }
        return "valuesLength=" + (valuesYaml == null ? 0 : valuesYaml.length())
                + " valuesHash=" + PortainerConnections.shortContentHash(valuesYaml);
    }

    private void summarize(
            PortainerBuildLogger log,
            long startedNs,
            String outcome,
            String release,
            String chartName,
            String chartVersion) {
        var fields = PortainerBuildLogger.summaryFields();
        if (outcome != null && !outcome.isBlank()) {
            fields.put("outcome", outcome);
        }
        if (release != null && !release.isBlank()) {
            fields.put("release", release);
        }
        if (chartName != null && !chartName.isBlank()) {
            fields.put("chart", chartName);
        }
        if (chartVersion != null && !chartVersion.isBlank()) {
            fields.put("version", chartVersion);
        }
        log.summaryWithDuration(startedNs, fields);
    }

    static void requireValidReleaseName(String raw) {
        String err = validateReleaseName(raw);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    static String validateReleaseName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Helm release name is required.";
        }
        String name = raw.trim();
        if (name.length() > 53 || !RELEASE_PATTERN.matcher(name).matches()) {
            return "Helm release name must be a DNS-1123 label (lowercase alphanumeric or '-', max 53).";
        }
        return null;
    }

    static void requireLooksLikeYaml(String content) {
        YamlLooksLike.require(
                content,
                "Values YAML content is required.",
                "Values content does not look like YAML (expected ':' or '---').");
    }

    private static final class HelmParsedInputs {
        final String release;
        final String namespace;
        final int endpoint;
        final String mode;
        final String chartRepo;
        final String chartVersion;
        final String chart;

        HelmParsedInputs(
                String release,
                String namespace,
                int endpoint,
                String mode,
                String chartRepo,
                String chartVersion,
                String chart) {
            this.release = release;
            this.namespace = namespace;
            this.endpoint = endpoint;
            this.mode = mode;
            this.chartRepo = chartRepo;
            this.chartVersion = chartVersion;
            this.chart = chart;
        }
    }

    private static final class ResolveValuesRequest {
        final String mode;
        final EnvVars buildEnv;
        final Item item;
        final FilePath workspace;
        final Launcher launcher;
        final TaskListener listener;
        final ValuesRepoLocals precomputed;

        ResolveValuesRequest(
                String mode,
                EnvVars buildEnv,
                Item item,
                FilePath workspace,
                Launcher launcher,
                TaskListener listener,
                ValuesRepoLocals precomputed) {
            this.mode = mode;
            this.buildEnv = buildEnv;
            this.item = item;
            this.workspace = workspace;
            this.launcher = launcher;
            this.listener = listener;
            this.precomputed = precomputed;
        }
    }

    private static final class ValuesRepoLocals {
        final String repoUrl;
        final String gitRef;
        final String path;

        private ValuesRepoLocals(String repoUrl, String gitRef, String path) {
            this.repoUrl = repoUrl;
            this.gitRef = gitRef;
            this.path = path;
        }

        static ValuesRepoLocals none() {
            return new ValuesRepoLocals(null, null, null);
        }
    }

    private static final class HelmDeployParams {
        final String release;
        final String chartName;
        final String chartRepo;
        final String namespace;
        final String chartVersion;
        final String valuesYaml;

        HelmDeployParams(
                String release,
                String chartName,
                String chartRepo,
                String namespace,
                String chartVersion,
                String valuesYaml) {
            this.release = release;
            this.chartName = chartName;
            this.chartRepo = chartRepo;
            this.namespace = namespace;
            this.chartVersion = chartVersion;
            this.valuesYaml = valuesYaml;
        }
    }

    private static final class ResolvedValues {
        final String content;
        final String repoUrl;
        final String gitRef;
        final String filePath;

        private ResolvedValues(String content, String repoUrl, String gitRef, String filePath) {
            this.content = content;
            this.repoUrl = repoUrl;
            this.gitRef = gitRef;
            this.filePath = filePath;
        }

        static ResolvedValues none() {
            return new ResolvedValues(null, null, null, null);
        }

        static ResolvedValues yaml(String content) {
            return new ResolvedValues(content, null, null, null);
        }

        static ResolvedValues repository(String content, String repoUrl, String gitRef, String filePath) {
            return new ResolvedValues(content, repoUrl, gitRef, filePath);
        }
    }

    @Symbol("portainerHelm")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Portainer Helm Deployment";
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
        public FormValidation doCheckReleaseName(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value != null && value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            String err = validateReleaseName(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckChart(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Helm chart name is required.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckRepo(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Chart repository URL is required.");
            }
            try {
                ChartRepositoryUrl.normalize(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckNamespace(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            String err = KubernetesNamespaces.validate(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckValuesRepositoryUrl(
                @QueryParameter String value,
                @QueryParameter String valuesSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (!HelmValuesSource.isRepository(HelmValuesSource.resolve(valuesSource, null))) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Values repository URL is required.");
            }
            try {
                GitRepositoryUrl.normalize(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckValuesFilePath(
                @QueryParameter String value,
                @QueryParameter String valuesSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (!HelmValuesSource.isRepository(HelmValuesSource.resolve(valuesSource, null))) {
                return FormValidation.ok();
            }
            String err = PortainerComposePath.validate(
                    value == null || value.isBlank() ? DEFAULT_VALUES_FILE : value,
                    VALUES_FILE_PATH_LABEL);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckValues(
                @QueryParameter String value,
                @QueryParameter String valuesSource,
                @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (!HelmValuesSource.isYaml(HelmValuesSource.resolve(valuesSource, value))) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Values YAML content is required for Manual YAML.");
            }
            try {
                requireLooksLikeYaml(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillValuesGitCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String valuesGitCredentialsId) {
            return PortainerCredentials.fillSecretOrUsernamePassword(item, valuesGitCredentialsId);
        }

        @POST
        public ListBoxModel doFillPortainerCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String portainerCredentialsId) {
            return PortainerCredentials.fillSecretText(item, portainerCredentialsId);
        }
    }
}
