package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST_YAML =
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n  namespace: apps\n";

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastCreateBody = new AtomicReference<>();
    private final AtomicBoolean stacksEmpty = new AtomicBoolean(true);
    private final AtomicInteger endpointType = new AtomicInteger(5);
    private final AtomicInteger stacksListCalls = new AtomicInteger(0);
    private final AtomicBoolean createCalled = new AtomicBoolean(false);
    private final AtomicBoolean putCalled = new AtomicBoolean(false);
    private final AtomicBoolean createReturnsStackId = new AtomicBoolean(false);
    private final AtomicReference<String> lastCreatePath = new AtomicReference<>();
    private final AtomicBoolean applicationsEmpty = new AtomicBoolean(false);

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        stacksEmpty.set(true);
        endpointType.set(5);
        stacksListCalls.set(0);
        createCalled.set(false);
        putCalled.set(false);
        createReturnsStackId.set(false);
        lastCreatePath.set(null);
        lastCreateBody.set(null);
        lastPath.set(null);
        lastMethod.set(null);
        applicationsEmpty.set(false);
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
            if (path != null && path.matches("/api/kubernetes/\\d+/applications$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (applicationsEmpty.get()) {
                    respond(exchange, 200, "[]");
                } else {
                    respond(exchange, 200, "[{\"Name\":\"demo\",\"StackId\":21,\"StackName\":\"web\"}]");
                }
                return;
            }
            if (path != null && path.endsWith("/api/stacks/create/kubernetes/string")) {
                createCalled.set(true);
                lastCreatePath.set(path);
                lastCreateBody.set(readBody(exchange));
                if (createReturnsStackId.get()) {
                    respond(exchange, 200, "{\"Id\":99,\"Name\":\"web\"}");
                } else {
                    stacksEmpty.set(false);
                    respond(exchange, 200, "{\"Output\":\"created\"}");
                }
                return;
            }
            if (path != null && path.endsWith("/api/stacks/create/kubernetes/repository")) {
                createCalled.set(true);
                lastCreatePath.set(path);
                lastCreateBody.set(readBody(exchange));
                stacksEmpty.set(false);
                respond(exchange, 200, "{\"Output\":\"created\"}");
                return;
            }
            if (path != null && path.matches("/api/stacks/\\d+$")
                    && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                putCalled.set(true);
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
    public void freestyle_createsManifestFromYaml_omitsNamespaceInBody(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(createCalled.get());
        assertFalse(putCalled.get());
        JsonNode body = MAPPER.readTree(lastCreateBody.get());
        assertFalse(body.has("Namespace"));
        assertEquals("web", body.path("StackName").asText());
        assertTrue(lastCreatePath.get().contains("/kubernetes/string"));
    }

    @Test
    public void freestyle_updatesWhenLiveResourcesExist(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stacksEmpty.set(false);
        applicationsEmpty.set(false);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(putCalled.get());
        assertFalse(createCalled.get());
    }

    @Test
    public void freestyle_emptyStackName_skipsFindByName(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertEquals(0, stacksListCalls.get());
        assertTrue(createCalled.get());
    }

    @Test
    public void freestyle_staleStack_failsWithoutUpdate(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        stacksEmpty.set(false);
        applicationsEmpty.set(true);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("no live Kubernetes resources were found", build);
        jenkins.assertLogContains("Remove the stale stack in Portainer and retry", build);
        assertFalse(putCalled.get());
        assertFalse(createCalled.get());
    }

    @Test
    public void freestyle_createWithoutLiveResources_fails(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        applicationsEmpty.set(true);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Deploy finished but no live Kubernetes resources were found", build);
        assertTrue(createCalled.get());
        assertFalse(putCalled.get());
    }

    @Test
    public void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertFalse(createCalled.get());
        assertFalse(putCalled.get());
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
                        + "    repositoryUrl: 'https://gitlab.example/group/manifests.git',\n"
                        + "    manifestFilePath: 'deploy.yaml'\n"
                        + "  )\n"
                        + "}\n",
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(createCalled.get());
        assertTrue(lastCreatePath.get().contains("/kubernetes/repository"));
        JsonNode body = MAPPER.readTree(lastCreateBody.get());
        assertFalse(body.has("Namespace"));
        assertEquals("deploy.yaml", body.path("ManifestFile").asText());
    }

    @Test
    public void pipeline_validateOnly_withoutNode(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        assertFalse(new PortainerManifestBuilder("1", "web").requiresWorkspace());
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "portainerManifest(\n"
                        + "  endpointId: '1',\n"
                        + "  stackName: 'web',\n"
                        + "  stackSource: 'yaml',\n"
                        + "  stackFileContent: 'apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: demo\\n',\n"
                        + "  validateOnly: true\n"
                        + ")\n",
                true));
        jenkins.buildAndAssertSuccess(job);
        assertFalse(createCalled.get());
    }

    @Test
    public void freestyle_rejectsDockerEndpoint(JenkinsRule jenkins) throws Exception {
        configurePortainer(jenkins);
        endpointType.set(1);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerManifestBuilder step = new PortainerManifestBuilder("1", "web");
        step.setStackSource(PortainerManifestBuilder.SOURCE_YAML);
        step.setStackFileContent(MANIFEST_YAML);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("not a Kubernetes Portainer environment", build);
        assertFalse(createCalled.get());
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
        assertEquals(FormValidation.Kind.ERROR, d.doCheckRepositoryUrl("", "repository", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStackFileContent("", "yaml", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStackFileContent(MANIFEST_YAML, "yaml", project).kind);
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

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
