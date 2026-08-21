package io.jenkins.plugins.portainer;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
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
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Freestyle / Pipeline step: ensure Docker Swarm secrets from Vault KV v2
 * ({@code @Symbol("portainerStackSecret")}; alias {@code portainerSwarmSecret}).
 */
public class PortainerSwarmSecretBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(PortainerSwarmSecretBuilder.class.getName());

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;

    private final String endpointId;

    /**
     * Vault KV key <em>names</em> to copy (one per line), not secret values.
     * Values are loaded from Vault at runtime and never persisted in this field.
     */
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String secretKeys = "";
    private VaultConnection vault;
    /** Former persisted field; migrated in {@link #readResolve()}. */
    private String vaultConnectionMode;
    private String vaultUrl;
    private String vaultAppRoleCredentialsId;
    private String vaultPath;
    private String vaultMount;
    private String vaultNamespace;
    private String vaultVersion;

    private String portainerConnectionMode;
    private String portainerUrl;
    private String portainerCredentialsId;
    private boolean verboseLogging;
    private boolean validateOnly;
    private boolean pruneOld;

    @DataBoundConstructor
    public PortainerSwarmSecretBuilder(String endpointId) {
        this.endpointId = endpointId == null ? "" : endpointId.trim();
    }

    private Object readResolve() {
        if (vault == null) {
            vault = VaultConnection.fromLegacy(
                    vaultConnectionMode,
                    vaultUrl,
                    vaultAppRoleCredentialsId,
                    vaultPath,
                    vaultMount,
                    vaultNamespace,
                    vaultVersion,
                    true);
        }
        if (vault instanceof VaultNone) {
            vault = new VaultInherit();
        }
        vaultConnectionMode = null;
        vaultUrl = null;
        vaultAppRoleCredentialsId = null;
        vaultPath = null;
        vaultMount = null;
        vaultNamespace = null;
        vaultVersion = null;
        return this;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getSecretKeys() {
        return secretKeys;
    }

    @DataBoundSetter
    public void setSecretKeys(String secretKeys) {
        this.secretKeys = secretKeys == null ? "" : secretKeys;
    }

    public VaultConnection getVault() {
        return vaultResolved();
    }

    @DataBoundSetter
    public void setVault(VaultConnection vault) {
        this.vault = vault instanceof VaultNone ? new VaultInherit() : vault;
    }

    private VaultConnection vaultResolved() {
        if (vault == null || vault instanceof VaultNone) {
            return new VaultInherit();
        }
        return vault;
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
            log.open(PortainerBuildLogger.TITLE_STACK_SECRET);
            performBody(run, buildEnv, log);
        }
    }

    private void performBody(
            Run<?, ?> run,
            EnvVars buildEnv,
            PortainerBuildLogger log) throws InterruptedException, IOException {
        long startedNs = System.nanoTime();

        final int endpoint = PortainerConnections.abortOn(
                log, () -> PortainerConnections.resolveEndpointId(endpointId, buildEnv));
        final List<String> keys = PortainerConnections.abortOn(
                log, () -> SwarmConfigNaming.parseSecretKeys(secretKeys));
        requireVaultConnection(log);

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
        final VaultFields vaultFields = PortainerConnections.abortOn(
                log, () -> vaultResolved().toFields(buildEnv));

        // Portainer → Vault → mutate (no Git on this step)
        PortainerBuildLogger.debugPortainerStart(log, connection, endpoint, null);

        try (PortainerClient client = new PortainerClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            PortainerConnections.runPreflight(client, connection, apiKey, endpoint, false, log);
            resolveSwarmOrAbort(client, connection, apiKey, endpoint, log);
            runVaultPreflight(run, buildEnv, item, connection, vaultFields, log);

            Map<String, String> vaultData = resolveVault(run, buildEnv, item, connection, vaultFields, log);
            requireNonEmptyVault(vaultData, vaultFields, log);
            assertVaultKeysMatch(keys, vaultData, vaultFields, log);

            List<SecretFile> files = buildSecretFiles(keys, vaultData);

            if (validateOnly) {
                logValidatePlan(log, files);
                summarize(log, startedNs, files.size(), 0, 0);
                return;
            }

            DesiredSecrets desiredSecrets = buildDesiredSecrets(files);

            SwarmNamedResource.Outcome outcome = SwarmNamedResource.ensure(
                    SwarmNamedResource.Kind.SECRET,
                    () -> client.listDockerSecrets(connection.baseUrl, apiKey, endpoint),
                    (name, data, labels) -> client.createDockerSecret(
                            connection.baseUrl,
                            apiKey,
                            endpoint,
                            new PortainerClient.DockerSecretCreateRequest(name, data, labels)),
                    desiredSecrets.desired,
                    null,
                    log);

            if (pruneOld) {
                SwarmNamedResource.pruneStaleByBaseLabel(
                        SwarmNamedResource.Kind.SECRET,
                        id -> client.removeDockerSecret(connection.baseUrl, apiKey, endpoint, id),
                        outcome.listed,
                        outcome.ensured,
                        log);
            }

            if (!desiredSecrets.envKeys.isEmpty()) {
                run.addAction(new PortainerSwarmConfigEnvAction(desiredSecrets.envKeys));
            }

            summarize(log, startedNs, files.size(), outcome.created, outcome.skipped);
        }
    }

    private void requireVaultConnection(PortainerBuildLogger log) throws AbortException {
        if (vaultResolved().isNone()) {
            throw PortainerConnections.abort(log, "Vault connection is required for Swarm secrets.");
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

    private void runVaultPreflight(
            Run<?, ?> run,
            EnvVars buildEnv,
            Item item,
            ResolvedConnection connection,
            VaultFields vaultFields,
            PortainerBuildLogger log) throws AbortException {
        VaultConnection vaultConnection = vaultResolved();
        log.debug(PortainerBuildLogger.formatVaultConnection(
                vaultConnection.getMode(),
                vaultFields.pathRaw,
                vaultFields.mount,
                vaultFields.version));
        VaultConnections.runPreflight(new VaultConnections.Request(
                vaultConnection.getMode(),
                true,
                vaultConnection.getVaultUrl(),
                vaultConnection.getVaultAppRoleCredentialsId(),
                vaultConnection.getVaultPath(),
                vaultConnection.getVaultNamespace(),
                run,
                buildEnv,
                item,
                connection.connectTimeoutMs,
                connection.readTimeoutMs,
                log));
    }

    private static void requireNonEmptyVault(
            Map<String, String> vaultData,
            VaultFields vaultFields,
            PortainerBuildLogger log) throws AbortException {
        if (vaultData.isEmpty()) {
            throw PortainerConnections.abort(
                    log,
                    "Vault path is empty: "
                            + (vaultFields.pathRaw == null ? "" : vaultFields.pathRaw));
        }
    }

    private static void assertVaultKeysMatch(
            List<String> keys,
            Map<String, String> vaultData,
            VaultFields vaultFields,
            PortainerBuildLogger log) throws AbortException {
        Set<String> stepSet = new LinkedHashSet<>(keys);
        Set<String> vaultSet = new LinkedHashSet<>(vaultData.keySet());
        Set<String> missing = new LinkedHashSet<>(stepSet);
        missing.removeAll(vaultSet);
        Set<String> extra = new LinkedHashSet<>(vaultSet);
        extra.removeAll(stepSet);
        log.info(PortainerBuildLogger.formatVaultPath(vaultFields.pathRaw, vaultFields.version));
        log.info("Keys configured in step - " + keys.size());
        log.info("Keys found in Vault - " + vaultData.size());
        log.debug("Keys configured in step: " + PortainerBuildLogger.formatNameList(keys));
        log.debug("Keys found in Vault: " + PortainerBuildLogger.formatNameList(vaultSet));
        if (missing.isEmpty() && extra.isEmpty()) {
            return;
        }
        log.error(PortainerBuildLogger.formatKeysDiffer(missing.size(), extra.size()));
        if (!missing.isEmpty()) {
            log.debug("Missing: " + PortainerBuildLogger.formatNameList(missing));
        }
        if (!extra.isEmpty()) {
            log.debug("Extra: " + PortainerBuildLogger.formatNameList(extra));
        }
        throw PortainerConnections.abort(
                log, PortainerBuildLogger.formatKeysDiffer(missing.size(), extra.size()));
    }

    private static List<SecretFile> buildSecretFiles(List<String> keys, Map<String, String> vaultData) {
        List<SecretFile> files = new ArrayList<>();
        for (String vaultKey : keys) {
            String value = vaultData.get(vaultKey);
            byte[] content = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
            String basename = SwarmConfigNaming.sanitizeBasename(vaultKey);
            files.add(new SecretFile(basename, content));
        }
        return files;
    }

    private static DesiredSecrets buildDesiredSecrets(List<SecretFile> files) {
        Map<String, String> envKeys = new LinkedHashMap<>();
        List<SwarmNamedResource.Desired> desired = new ArrayList<>();
        for (SecretFile file : files) {
            String hash = SwarmConfigNaming.hash8(file.content);
            String secretName = SwarmConfigNaming.configName(file.basename, file.content);
            envKeys.put(SwarmConfigNaming.envKeyForBasename(file.basename), secretName);
            desired.add(new SwarmNamedResource.Desired(file.basename, secretName, hash, file.content));
        }
        return new DesiredSecrets(desired, envKeys);
    }

    private void logValidatePlan(PortainerBuildLogger log, List<SecretFile> files) {
        log.info("Validate-only — skipping Docker secret mutations");
        for (SecretFile file : files) {
            String secretName = SwarmConfigNaming.configName(file.basename, file.content);
            log.debug("(would create) " + secretName);
        }
    }

    private Map<String, String> resolveVault(
            Run<?, ?> run,
            EnvVars buildEnv,
            Item item,
            ResolvedConnection connection,
            VaultFields fields,
            PortainerBuildLogger log) throws AbortException {
        VaultConnection vaultConnection = vaultResolved();
        return VaultKv.resolve(new VaultKv.Request(
                new VaultKv.Request.VaultSpec(
                        VaultKv.Policy.REQUIRED,
                        vaultConnection.getMode(),
                        fields,
                        vaultConnection.getVaultAppRoleCredentialsId()),
                new VaultKv.Request.RunContext(run, buildEnv, item),
                new VaultKv.Request.Timeouts(connection.connectTimeoutMs, connection.readTimeoutMs),
                log));
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

    private static final class SecretFile {
        final String basename;
        final byte[] content;

        SecretFile(String basename, byte[] content) {
            this.basename = basename;
            this.content = content;
        }
    }

    private static final class DesiredSecrets {
        final List<SwarmNamedResource.Desired> desired;
        final Map<String, String> envKeys;

        DesiredSecrets(List<SwarmNamedResource.Desired> desired, Map<String, String> envKeys) {
            this.desired = desired;
            this.envKeys = envKeys;
        }
    }

    @Symbol({"portainerStackSecret", "portainerSwarmSecret"})
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Portainer Stack Secret";
        }

        public String getPortainerConnectionSummary() {
            return PortainerConnections.connectionSummary();
        }

        public boolean isVaultPluginPresent() {
            return VaultPluginInherit.isPluginPresent();
        }

        public boolean isVaultInheritReady() {
            return VaultPluginInherit.isPluginPresent() && VaultPluginInherit.isSystemConfigured();
        }

        public String getVaultInheritSummary() {
            return VaultPluginInherit.inheritSummary();
        }

        public List<Descriptor<VaultConnection>> getVaultDescriptors() {
            return VaultConnection.descriptors(false);
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
        public FormValidation doCheckSecretKeys(@QueryParameter String value, @AncestorInPath Item item) {
            PortainerConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Secret keys are required (one Vault KV key per line).");
            }
            try {
                SwarmConfigNaming.parseSecretKeys(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillPortainerCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String portainerCredentialsId) {
            return PortainerCredentials.fillSecretText(item, portainerCredentialsId);
        }
    }
}
