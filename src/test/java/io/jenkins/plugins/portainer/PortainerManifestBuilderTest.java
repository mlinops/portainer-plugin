package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.FormValidation;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class PortainerManifestBuilderTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicBoolean stacksEmpty = new AtomicBoolean(true);
    private final AtomicInteger endpointType = new AtomicInteger(5);
    private final AtomicInteger stacksListCalls = new AtomicInteger(0);
    private final AtomicBoolean createCalled = new AtomicBoolean(false);
    private final AtomicBoolean createReturnsStackId = new AtomicBoolean(false);
    private final AtomicReference<String> lastCreatePath = new AtomicReference<>();
    private final AtomicBoolean namespaceGetCalled = new AtomicBoolean(false);
    private final AtomicBoolean namespaceCreateCalled = new AtomicBoolean(false);
    private final AtomicBoolean namespaceExists = new AtomicBoolean(true);

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        stacksEmpty.set(true);
        endpointType.set(5);
        stacksListCalls.set(0);
        createCalled.set(false);
        createReturnsStackId.set(false);
        lastCreatePath.set(null);
        lastPath.set(null);
        lastMethod.set(null);
        namespaceGetCalled.set(false);
        namespaceCreateCalled.set(false);
        namespaceExists.set(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastMethod.set(exchange.getRequestMethod());
            String path = exchange.getRequestURI().getPath();
            if ("/api/status".equals(path)) {
                respond(exchange, 200, "{\"Version\":\"2.39.3\"}");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+$")) {
                respond(exchange, 200,
                        "{\"Id\":1,\"Name\":\"k8s\",\"Type\":" + endpointType.get() + "}");
                return;
            }
            if ("/api/endpoints".equals(path)) {
                respond(exchange, 200, "[{\"Id\":1,\"Name\":\"k8s\",\"Type\":5}]");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+/kubernetes/version$")) {
                respond(exchange, 200, "{\"major\":\"1\",\"minor\":\"28\"}");
                return;
            }
            if (path != null && path.matches("/api/kubernetes/\\d+/namespaces/[^/]+$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                namespaceGetCalled.set(true);
                if (namespaceExists.get()) {
                    respond(exchange, 200, "{\"Name\":\"apps\"}");
                } else {
                    respond(exchange, 404, "{\"message\":\"not found\"}");
                }
                return;
            }
            if (path != null && path.matches("/api/kubernetes/\\d+/namespaces$")
                    && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                namespaceCreateCalled.set(true);
                namespaceExists.set(true);
                respond(exchange, 200, "{\"Name\":\"apps\"}");
                return;
            }
            if (path != null && path.endsWith("/api/stacks/create/kubernetes/string")) {
                createCalled.set(true);
                lastCreatePath.set(path);
                if (createReturnsStackId.get()) {
                    respond(exchange, 200, "{\"Id\":99,\"Name\":\"web\"}");
                } else {
                    // K8s create often returns Output only; id resolved via post-create find-by-name.
                    stacksEmpty.set(false);
                    respond(exchange, 200, "{\"Output\":\"created\"}");
                }
                return;
            }
            if (path != null && path.endsWith("/api/stacks/create/kubernetes/repository")) {
                createCalled.set(true);
                lastCreatePath.set(path);
                if (createReturnsStackId.get()) {
                    respond(exchange, 200, "{\"Id\":99,\"Name\":\"web\"}");
                } else {
                    stacksEmpty.set(false);
                    respond(exchange, 200, "{\"Output\":\"created\"}");
                }
                return;
            }
            if (path != null && path.matches("/api/stacks/\\d+$")
                    && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                createCalled.set(true);
                respond(exchange, 200, "{\"Id\":21,\"Name\":\"web\"}");
                return;
            }
            if ("/api/stacks".equals(path)) {
                stacksListCalls.incrementAndGet();
                if (stacksEmpty.get()) {
                    respond(exchange, 200, "[]");
                } else {
                    respond(exchange, 200, "[{\"Id\":21,\"Name\":\"web\",\"EndpointId\":1}]");
                }
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void ensureNamespace_defaultsTrue() {
        assertTrue(new PortainerManifestBuilder("1", "web").isEnsureNamespace());
    }

    @Test
    public void freestyle_createsManifestFromYaml(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setVerboseLogging(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("======== Portainer Manifest Deployment ========", build);
        jenkins.assertLogContains("[INFO] Manifest name=web namespace=apps", build);
        jenkins.assertLogContains("[INFO] Preflight check of endpoint", build);
        jenkins.assertLogContains("Stack not found by name", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created stackId=21", build);
        jenkins.assertLogNotContains("Stack created", build);
        jenkins.assertLogNotContains("Manifest created", build);
        jenkins.assertLogNotContains("Manifest stack name is empty", build);
        jenkins.assertLogNotContains("Manifest stack id=", build);
        jenkins.assertLogNotContains("Manifest stack name not found", build);
        jenkins.assertLogNotContains("completed successfully", build);
        jenkins.assertLogNotContains("apiKey", build);
        assertEquals(2, stacksListCalls.get());
        assertTrue(createCalled.get());
        assertTrue(lastCreatePath.get() != null && lastCreatePath.get().contains("/kubernetes/string"));
    }

    @Test
    public void freestyle_emptyStackName_skipsFindByName(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setEnsureNamespace(false);
        step.setVerboseLogging(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Stack name is empty", build);
        jenkins.assertLogNotContains("Manifest stack name is empty", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created", build);
        jenkins.assertLogNotContains("stackId=", build);
        jenkins.assertLogNotContains("Stack not found by name", build);
        jenkins.assertLogNotContains("Manifest created", build);
        assertEquals(0, stacksListCalls.get());
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_createResponseId_skipsPostCreateFind(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        createReturnsStackId.set(true);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setEnsureNamespace(false);
        step.setVerboseLogging(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Stack not found by name", build);
        jenkins.assertLogContains("[INFO] Summary outcome=created stackId=99", build);
        jenkins.assertLogNotContains("Manifest created", build);
        assertEquals(1, stacksListCalls.get());
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("[INFO] Preflight check of endpoint", build);
        jenkins.assertLogContains("[INFO] Validate-only — skipping deploy", build);
        jenkins.assertLogNotContains("Would create-or-update manifest from YAML", build);
        jenkins.assertLogContains("[INFO] Summary outcome=validated", build);
        jenkins.assertLogNotContains("Stack created", build);
        assertTrue(!createCalled.get());
        assertTrue(lastCreatePath.get() == null);
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertFalse(isManifestMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void freestyle_ensureNamespace_createsMissingThenDeploys(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        namespaceExists.set(false);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setEnsureNamespace(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Ensuring namespace=apps", build);
        jenkins.assertLogContains("Namespace ready name=apps result=created", build);
        jenkins.assertLogContains("Summary outcome=created stackId=21", build);
        jenkins.assertLogNotContains("Stack created", build);
        assertEquals(2, stacksListCalls.get());
        assertTrue(namespaceGetCalled.get());
        assertTrue(namespaceCreateCalled.get());
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_ensureNamespace_existingIsNoOp(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setEnsureNamespace(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Ensuring namespace=apps", build);
        jenkins.assertLogContains("Namespace ready name=apps result=existed", build);
        assertTrue(namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_validateOnly_withEnsureNamespace_skipsEnsureHttp(JenkinsRule jenkins)
            throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setEnsureNamespace(true);
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("[INFO] Validate-only — skipping deploy", build);
        jenkins.assertLogNotContains("Would ensure namespace=apps", build);
        jenkins.assertLogContains("Summary outcome=validated", build);
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(!createCalled.get());
        assertTrue(lastCreatePath.get() == null);
    }

    @Test
    public void freestyle_ensureNamespaceFalse_skipsNamespaceHttp(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setNamespace("apps");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        step.setEnsureNamespace(false);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=created", build);
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(createCalled.get());
        assertTrue(lastCreatePath.get() != null && lastCreatePath.get().contains("/kubernetes/string"));
    }

    @Test
    public void pipeline_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-validate-only");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  portainerManifest(\n"
                        + "    endpointId: '1',\n"
                        + "    stackName: 'web',\n"
                        + "    namespace: 'default',\n"
                        + "    stackSource: 'yaml',\n"
                        + "    stackFileContent: 'apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: demo\\n',\n"
                        + "    validateOnly: true\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping deploy", run);
        jenkins.assertLogContains("Summary outcome=validated", run);
        assertTrue(!createCalled.get());
        assertTrue(lastCreatePath.get() == null);
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertFalse(isManifestMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void pipeline_withoutNode_validateOnly(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        assertFalse(new PortainerManifestBuilder("1", "web").requiresWorkspace());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "portainerManifest(\n"
                        + "  endpointId: '1',\n"
                        + "  stackName: 'web',\n"
                        + "  namespace: 'default',\n"
                        + "  stackSource: 'yaml',\n"
                        + "  stackFileContent: 'apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: demo\\n',\n"
                        + "  validateOnly: true\n"
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Validate-only — skipping deploy", run);
        jenkins.assertLogContains("Summary outcome=validated", run);
        assertTrue(!createCalled.get());
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertFalse(isManifestMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    public void pipeline_createsManifestFromRepository(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-pipe");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  portainerManifest(\n"
                        + "    endpointId: '1',\n"
                        + "    stackName: 'web',\n"
                        + "    namespace: 'default',\n"
                        + "    repositoryUrl: 'https://gitlab.example/group/manifests.git',\n"
                        + "    manifestFilePath: 'deploy.yaml',\n"
                        + "    verboseLogging: true\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("[INFO] Manifest name=", run);
        jenkins.assertLogContains("Summary outcome=created stackId=21", run);
        jenkins.assertLogContains("Stack not found by name", run);
        jenkins.assertLogNotContains("Stack created", run);
        jenkins.assertLogNotContains("Manifest created", run);
        assertEquals(2, stacksListCalls.get());
        assertTrue(createCalled.get());
        assertTrue(lastCreatePath.get() != null && lastCreatePath.get().contains("/kubernetes/repository"));
    }

    @Test
    public void freestyle_rejectsDockerEndpoint(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        endpointType.set(1);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent("kind: Pod\napiVersion: v1\nmetadata:\n  name: x\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("not a Kubernetes Portainer environment", build);
        jenkins.assertLogContains("Type=1", build);
    }

    @Test
    public void descriptor_displayName(JenkinsRule jenkins) {
        assertEquals(
                "Portainer Manifest Deployment",
                jenkins.jenkins.getDescriptorByType(PortainerManifestBuilder.DescriptorImpl.class)
                        .getDisplayName());
    }

    @Test
    public void formValidation_manifestPathAndContent(JenkinsRule jenkins) throws Exception {
        PortainerManifestBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerManifestBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckManifestFilePath("../secret.yaml", "repository", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckManifestFilePath("", "yaml", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckRepositoryUrl("", "repository", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckRepositoryUrl("https://u:p@gitlab.example/group/m.git", "repository", project)
                        .kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckRepositoryUrl("", "yaml", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStackFileContent("", "yaml", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStackFileContent("", "repository", project).kind);
    }

    private void configurePortainer(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "portainer-api-key",
                        "portainer",
                        Secret.fromString("test-token")));
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setName("lab");
        cfg.setPortainerUrl(base);
        cfg.setCredentialsId("portainer-api-key");
        cfg.save();
    }

    private static boolean isManifestMutateApi(String path, String method) {
        if (path == null) {
            return false;
        }
        if (path.contains("/kubernetes/string")
                || path.contains("/kubernetes/repository")
                || path.contains("/namespaces")) {
            return true;
        }
        return path.matches(".*/api/stacks/\\d+$") && "PUT".equalsIgnoreCase(method);
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
