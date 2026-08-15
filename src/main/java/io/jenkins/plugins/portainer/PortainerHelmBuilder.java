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
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
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
    public static final String DEFAULT_NAMESPACE = PortainerManifestBuilder.DEFAULT_NAMESPACE;
    public static final String DEFAULT_VALUES_FILE = "values.yaml";
    public static final String VALUES_NONE = HelmValuesSource.NONE;
    public static final String VALUES_REPOSITORY = HelmValuesSource.REPOSITORY;
    public static final String VALUES_YAML = HelmValuesSource.YAML;

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
        PortainerBuildLogger log = new PortainerBuildLogger(LOGGER, listener, verboseLogging);
        log.open(PortainerBuildLogger.TITLE_HELM);
        try {
            performBody(run, buildEnv, workspace, launcher, listener, log);
        } finally {
            log.close();
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

        final String expandedRelease = PortainerConnections.abortOn(log, () -> {
            String name = buildEnv.expand(releaseName == null ? "" : releaseName).trim();
            requireValidReleaseName(name);
            return name;
        });
        final String expandedNamespace = PortainerConnections.abortOn(
                log, () -> PortainerManifestBuilder.resolveNamespace(namespace, buildEnv));
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

        // Portainer → Git (values) → mutate
        PortainerBuildLogger.debugPortainerStart(
                log,
                connection,
                endpoint,
                "chartRepo=" + chartRepo
                        + (chartVersion == null || chartVersion.isBlank() ? "" : " version=" + chartVersion)
                        + " valuesSource=" + mode
                        + " atomic=" + atomic
                        + " forceReinstall=" + forceReinstall);

        try (PortainerClient client = new PortainerClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            PortainerConnections.runPreflight(client, connection, apiKey, endpoint, true, true, log);

            String valuesRepoUrl = null;
            String valuesGitRef = null;
            String valuesPath = null;
            if (HelmValuesSource.isRepository(mode)) {
                valuesRepoUrl = PortainerConnections.abortOn(log, () -> GitRepositoryUrl.normalize(
                        buildEnv.expand(valuesRepositoryUrl == null ? "" : valuesRepositoryUrl)));
                valuesGitRef = PortainerStackBuilder.resolveRepositoryReference(
                        valuesRepositoryReferenceName, buildEnv);
                valuesPath = PortainerConnections.abortOn(log, () -> PortainerComposePath.normalize(
                        valuesFilePath == null || valuesFilePath.isBlank()
                                ? DEFAULT_VALUES_FILE
                                : buildEnv.expand(valuesFilePath),
                        "Values file path"));
                PortainerBuildLogger.logGitPreflight(
                        log, valuesGitRef, valuesRepoUrl, valuesPath, null, null, null);
            }

            if (!HelmValuesSource.isNone(mode)) {
                log.info("Loading values");
            }
            final String valuesYaml;
            try {
                ResolvedValues resolved = resolveValues(
                        mode, buildEnv, item, workspace, launcher, listener,
                        valuesRepoUrl, valuesGitRef, valuesPath);
                valuesYaml = resolved.content;
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw PortainerConnections.abort(log, e.getMessage());
            } catch (IOException e) {
                throw PortainerConnections.abort(log, PortainerConnections.truncateMessage(e), e, true);
            }

            String expandedChart = buildEnv.expand(chart).trim();
            log.info("Release name=" + expandedRelease
                    + " chart=" + expandedChart
                    + " namespace=" + expandedNamespace);
            log.info("Values source=" + mode);
            String valuesDebug = valuesDebugLine(mode, valuesYaml);
            if (valuesDebug != null) {
                log.debug(valuesDebug);
            }

            if (validateOnly) {
                log.info("Validate-only — skipping deploy");
                if (ensureNamespace) {
                    log.debug("Would ensure namespace=" + expandedNamespace);
                }
                log.debug("Would "
                        + (forceReinstall ? "force-reinstall" : "install-or-upgrade")
                        + " helm release=" + expandedRelease
                        + " chart=" + expandedChart
                        + " namespace=" + expandedNamespace
                        + (chartVersion == null || chartVersion.isBlank() ? "" : " version=" + chartVersion)
                        + " valuesSource=" + mode);
                summarize(log, startedNs, "validated", expandedRelease, expandedChart, chartVersion);
                return;
            }

            try {
                String outcome = deployHelm(
                        client,
                        connection,
                        apiKey,
                        endpoint,
                        expandedRelease,
                        expandedChart,
                        chartRepo,
                        expandedNamespace,
                        chartVersion,
                        valuesYaml,
                        log);
                summarize(
                        log, startedNs, outcome, expandedRelease, expandedChart, chartVersion);
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
    }

    private String deployHelm(
            PortainerClient client,
            ResolvedConnection connection,
            String apiKey,
            int endpoint,
            String release,
            String chartName,
            String chartRepo,
            String namespace,
            String chartVersion,
            String valuesYaml,
            PortainerBuildLogger log) throws IOException {
        if (ensureNamespace) {
            KubernetesNamespaces.ensure(client, connection, apiKey, endpoint, namespace, log);
        }
        boolean exists = client.helmReleaseExists(
                connection.baseUrl, apiKey, endpoint, release, namespace);
        if (forceReinstall) {
            if (exists) {
                log.info("Helm force reinstall — uninstalling then installing release=" + release);
                client.uninstallHelmRelease(
                        connection.baseUrl, apiKey, endpoint, release, namespace);
                log.info("Helm release uninstalled name=" + release);
            } else {
                log.debug("Helm release name not found");
            }
        }
        log.info("Ensuring Helm release");
        client.installHelmChart(
                connection.baseUrl,
                apiKey,
                endpoint,
                new PortainerClient.HelmInstallRequest(
                        release,
                        chartName,
                        chartRepo,
                        namespace,
                        chartVersion,
                        valuesYaml,
                        atomic));
        return exists ? "updated" : "created";
    }

    private ResolvedValues resolveValues(
            String mode,
            EnvVars buildEnv,
            Item item,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            String precomputedRepoUrl,
            String precomputedGitRef,
            String precomputedPath) throws IOException, InterruptedException {
        if (HelmValuesSource.isNone(mode)) {
            return ResolvedValues.none();
        }
        if (HelmValuesSource.isYaml(mode)) {
            if (values == null || values.isBlank()) {
                throw new IllegalArgumentException("Values YAML content is required for Manual YAML source.");
            }
            requireLooksLikeYaml(values);
            return ResolvedValues.yaml(values);
        }
        // repository — agent workspace + Launcher required for shallow clone
        if (workspace == null || launcher == null) {
            throw new IllegalStateException(
                    "Helm Values from repository require an agent workspace (wrap the step in node { } / agent any).");
        }
        String repoUrl = precomputedRepoUrl != null
                ? precomputedRepoUrl
                : GitRepositoryUrl.normalize(
                        buildEnv.expand(valuesRepositoryUrl == null ? "" : valuesRepositoryUrl));
        String path = precomputedPath != null
                ? precomputedPath
                : PortainerComposePath.normalize(
                        valuesFilePath == null || valuesFilePath.isBlank()
                                ? DEFAULT_VALUES_FILE
                                : buildEnv.expand(valuesFilePath),
                        "Values file path");
        String gitRef = precomputedGitRef != null
                ? precomputedGitRef
                : PortainerStackBuilder.resolveRepositoryReference(
                        valuesRepositoryReferenceName, buildEnv);
        PortainerCredentials.GitAuth gitAuth =
                PortainerConnections.resolveOptionalGitAuth(valuesGitCredentialsId, item);
        String content = GitRepositoryFiles.readFile(
                repoUrl, gitRef, path, gitAuth, workspace, launcher, listener);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Values file from repository is empty: " + path);
        }
        requireLooksLikeYaml(content);
        return ResolvedValues.repository(content, repoUrl, gitRef, path);
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

        @Override
        public Builder newInstance(StaplerRequest2 req, JSONObject formData) throws Descriptor.FormException {
            if (formData != null) {
                ConnectionMode.flattenRadioBlock(
                        formData,
                        "portainerConnectionMode",
                        "portainerUrl",
                        "portainerCredentialsId");
                HelmValuesSource.flattenRadioBlock(formData);
            }
            return super.newInstance(req, formData);
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
            String err = PortainerManifestBuilder.validateNamespace(value);
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
                    "Values file path");
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
