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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class PortainerHelmBuilderTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastInstallBody = new AtomicReference<>();
    private final AtomicBoolean releaseExists = new AtomicBoolean(false);
    private final AtomicInteger endpointType = new AtomicInteger(5);
    private final AtomicBoolean installCalled = new AtomicBoolean(false);
    private final AtomicBoolean uninstallCalled = new AtomicBoolean(false);
    private final AtomicBoolean helmListCalled = new AtomicBoolean(false);
    private final AtomicBoolean namespaceGetCalled = new AtomicBoolean(false);
    private final AtomicBoolean namespaceCreateCalled = new AtomicBoolean(false);
    private final AtomicBoolean namespaceExists = new AtomicBoolean(true);
    private final List<String> helmMutationOrder = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        releaseExists.set(false);
        endpointType.set(5);
        installCalled.set(false);
        uninstallCalled.set(false);
        helmListCalled.set(false);
        namespaceGetCalled.set(false);
        namespaceCreateCalled.set(false);
        namespaceExists.set(true);
        lastInstallBody.set(null);
        lastPath.set(null);
        lastMethod.set(null);
        helmMutationOrder.clear();
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
                    respond(exchange, 200, "{\"Name\":\"default\"}");
                } else {
                    respond(exchange, 404, "{\"message\":\"not found\"}");
                }
                return;
            }
            if (path != null && path.matches("/api/kubernetes/\\d+/namespaces$")
                    && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                namespaceCreateCalled.set(true);
                namespaceExists.set(true);
                respond(exchange, 200, "{\"Name\":\"default\"}");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+/kubernetes/helm$")) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    helmListCalled.set(true);
                    if (releaseExists.get()) {
                        respond(exchange, 200,
                                "[{\"Name\":\"nginx\",\"Namespace\":\"default\"}]");
                    } else {
                        respond(exchange, 200, "[]");
                    }
                    return;
                }
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    installCalled.set(true);
                    helmMutationOrder.add("POST");
                    lastInstallBody.set(new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 201, "{\"name\":\"nginx\",\"namespace\":\"default\"}");
                    return;
                }
            }
            if (path != null && path.matches("/api/endpoints/\\d+/kubernetes/helm/[^/]+$")
                    && "DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                uninstallCalled.set(true);
                helmMutationOrder.add("DELETE");
                respond(exchange, 200, "{}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ensureNamespace_defaultsTrue() {
        assertTrue(new PortainerHelmBuilder(
                        "1", "nginx", "nginx", "https://charts.example/bitnami")
                .isEnsureNamespace());
    }

    @Test
    void valuesSource_defaultsNone_andMigratesBareValues() {
        PortainerHelmBuilder bare = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        assertEquals(PortainerHelmBuilder.VALUES_NONE, bare.getValuesSource());
        bare.setValues("replicaCount: 1\n");
        assertEquals(PortainerHelmBuilder.VALUES_YAML, bare.getValuesSource());
        bare.setValuesSource(PortainerHelmBuilder.VALUES_NONE);
        assertEquals(PortainerHelmBuilder.VALUES_NONE, bare.getValuesSource());
    }

    @Test
    void freestyle_noValuesSource_omitsValuesInBody(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("default");
        step.setValuesSource(PortainerHelmBuilder.VALUES_NONE);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(installCalled.get());
        String body = lastInstallBody.get();
        assertTrue(body != null && !body.contains("\"values\""));
    }

    @Test
    void freestyle_manualYaml_sendsValues(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("default");
        step.setValuesSource(PortainerHelmBuilder.VALUES_YAML);
        step.setValues("replicaCount: 1\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("replicaCount", build);
        assertTrue(installCalled.get());
        assertTrue(lastInstallBody.get().contains("\"values\""));
        assertTrue(lastInstallBody.get().contains("replicaCount"));
    }

    @Test
    void freestyle_repository_fetchesThenSendsValues(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("default");
        step.setValuesSource(PortainerHelmBuilder.VALUES_REPOSITORY);
        step.setValuesRepositoryUrl("https://gitlab.example/group/helm-values.git");
        step.setValuesFilePath("values.yaml");
        step.setValuesRepositoryReferenceName("refs/heads/main");
        step.gitReader = (url, ref, path, ctx) -> "replicaCount: 2\n";
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("replicaCount", build);
        assertTrue(installCalled.get());
        assertTrue(lastInstallBody.get().contains("replicaCount: 2"));
    }

    @Test
    void freestyle_installsWhenMissing(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("default");
        step.setValues("replicaCount: 1\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("replicaCount", build);
        assertTrue(installCalled.get());
        assertTrue(!uninstallCalled.get());
        assertTrue(helmListCalled.get());
    }

    @Test
    void pipeline_valuesSourceNone_andYaml(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        WorkflowJob noneJob = jenkins.createProject(WorkflowJob.class, "helm-values-none");
        noneJob.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerHelm(
                    endpointId: '1',
                    releaseName: 'nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/bitnami',
                    namespace: 'default',
                    valuesSource: 'none'
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(noneJob);
        assertTrue(lastInstallBody.get() != null && !lastInstallBody.get().contains("\"values\""));

        installCalled.set(false);
        lastInstallBody.set(null);
        WorkflowJob yamlJob = jenkins.createProject(WorkflowJob.class, "helm-values-yaml");
        yamlJob.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerHelm(
                    endpointId: '1',
                    releaseName: 'nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/bitnami',
                    namespace: 'default',
                    valuesSource: 'yaml',
                    values: 'replicaCount: 3\\n'
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(yamlJob);
        assertTrue(lastInstallBody.get().contains("replicaCount: 3"));
    }

    @Test
    void freestyle_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("default");
        step.setValues("replicaCount: 1\n");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(!installCalled.get());
        assertTrue(!uninstallCalled.get());
        assertTrue(!helmListCalled.get());
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(helmMutationOrder.isEmpty());
        assertFalse(isHelmMutateApi(lastPath.get(), lastMethod.get()));
    }

    @Test
    void freestyle_ensureNamespace_createsMissingThenInstalls(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        namespaceExists.set(false);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("apps");
        step.setEnsureNamespace(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(namespaceGetCalled.get());
        assertTrue(namespaceCreateCalled.get());
        assertTrue(installCalled.get());
    }

    @Test
    void freestyle_validateOnly_withEnsureNamespace_skipsEnsureHttp(JenkinsRule jenkins)
            throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("apps");
        step.setEnsureNamespace(true);
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(!installCalled.get());
        assertTrue(helmMutationOrder.isEmpty());
    }

    @Test
    void freestyle_ensureNamespaceFalse_skipsNamespaceHttp(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("apps");
        step.setEnsureNamespace(false);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(installCalled.get());
        assertEquals(List.of("POST"), helmMutationOrder);
    }

    @Test
    void pipeline_validateOnly_skipsMutatingApis(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "helm-validate-only");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerHelm(
                    endpointId: '1',
                    releaseName: 'nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/bitnami',
                    namespace: 'default',
                    validateOnly: true
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(!installCalled.get());
        assertTrue(!helmListCalled.get());
        assertTrue(!uninstallCalled.get());
        assertTrue(!namespaceGetCalled.get());
        assertTrue(!namespaceCreateCalled.get());
        assertTrue(helmMutationOrder.isEmpty());
    }

    @Test
    void pipeline_withoutNode_validateOnly_noneValues(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        assertFalse(step.requiresWorkspace());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "helm-no-node");
        job.setDefinition(new CpsFlowDefinition(
                """
                portainerHelm(
                  endpointId: '1',
                  releaseName: 'nginx',
                  chart: 'nginx',
                  repo: 'https://charts.example/bitnami',
                  namespace: 'default',
                  valuesSource: 'none',
                  validateOnly: true
                )
                """,
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogNotContains("no workspace", run);
        assertTrue(!installCalled.get());
        assertTrue(!namespaceGetCalled.get());
        assertTrue(helmMutationOrder.isEmpty());
    }

    @Test
    void pipeline_upgradesWhenExists_withoutDelete(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        releaseExists.set(true);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "helm-pipe");
        job.setDefinition(new CpsFlowDefinition(
                """
                node {
                  portainerHelm(
                    endpointId: '1',
                    releaseName: 'nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/bitnami',
                    namespace: 'default'
                  )
                }
                """,
                true));
        jenkins.buildAndAssertSuccess(job);
        assertTrue(!uninstallCalled.get());
        assertTrue(installCalled.get());
        assertTrue(helmListCalled.get());
    }

    @Test
    void freestyle_forceReinstall_deletesThenPosts(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        releaseExists.set(true);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setNamespace("default");
        step.setForceReinstall(true);
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(uninstallCalled.get());
        assertTrue(installCalled.get());
        assertTrue(helmListCalled.get());
        assertEquals(List.of("DELETE", "POST"), helmMutationOrder);
    }

    @Test
    void freestyle_rejectsDockerEndpoint(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        endpointType.set(2);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("not a Kubernetes Portainer environment", build);
    }

    @Test
    void freestyle_listHelm500_showsDetailsHintAndStackTrace(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/api/status".equals(path)) {
                respond(exchange, 200, "{\"Version\":\"2.39.3\"}");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+$")) {
                respond(exchange, 200, "{\"Id\":1,\"Name\":\"k8s\",\"Type\":5}");
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
            if (path != null && path.matches("/api/endpoints/\\d+/kubernetes/helm$")
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(
                        exchange,
                        500,
                        "{\"message\":\"Helm returned an error\","
                                + "\"details\":\"Failed to list helm releases: Kubernetes cluster unreachable: "
                                + "Get \\\"https://portainer.test.local:9000/api/endpoints/326/kubernetes/version\\\": "
                                + "http: server gave HTTP response to HTTPS client\"}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setPortainerUrl(base);
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        step.setEnsureNamespace(false);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("[ERROR] Helm operation failed: HTTP 500", build);
        jenkins.assertLogNotContains("ERROR: Portainer:", build);
        jenkins.assertLogContains("Kubernetes cluster unreachable", build);
        jenkins.assertLogContains("HTTP response to HTTPS client", build);
        jenkins.assertLogContains("Hint:", build);
        jenkins.assertLogContains("TLS mismatch", build);
        jenkins.assertLogContains("at io.jenkins.plugins.portainer.PortainerClient", build);
        jenkins.assertLogNotContains("test-token", build);
    }

    @Test
    void freestyle_k8sPreflightFailure_showsHint(JenkinsRule jenkins) throws Exception {
        configurePortainer();
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/api/status".equals(path)) {
                respond(exchange, 200, "{\"Version\":\"2.39.3\"}");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+$")) {
                respond(exchange, 200, "{\"Id\":1,\"Name\":\"k8s\",\"Type\":5}");
                return;
            }
            if ("/api/endpoints".equals(path)) {
                respond(exchange, 200, "[{\"Id\":1,\"Name\":\"k8s\",\"Type\":5}]");
                return;
            }
            if (path != null && path.matches("/api/endpoints/\\d+/kubernetes/version$")) {
                respond(
                        exchange,
                        500,
                        "{\"message\":\"Kubernetes cluster unreachable\","
                                + "\"details\":\"http: server gave HTTP response to HTTPS client\"}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        PortainerGlobalConfiguration cfg = PortainerGlobalConfiguration.get();
        cfg.setPortainerUrl(base);
        cfg.save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        PortainerHelmBuilder step = new PortainerHelmBuilder(
                "1", "nginx", "nginx", "https://charts.example/bitnami");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Preflight failed:", build);
        jenkins.assertLogContains("Hint:", build);
        jenkins.assertLogContains("TLS mismatch", build);
        jenkins.assertLogContains("fix in Portainer", build);
    }

    @Test
    void descriptor_displayName(JenkinsRule jenkins) {
        assertEquals(
                "Portainer Helm Deployment",
                jenkins.jenkins.getDescriptorByType(PortainerHelmBuilder.DescriptorImpl.class)
                        .getDisplayName());
        PortainerHelmBuilder none = new PortainerHelmBuilder("1", "rel", "chart", "https://charts.example");
        assertFalse(none.requiresWorkspace());
        PortainerHelmBuilder yaml = new PortainerHelmBuilder("1", "rel", "chart", "https://charts.example");
        yaml.setValuesSource("yaml");
        yaml.setValues("replicaCount: 1\n");
        assertFalse(yaml.requiresWorkspace());
        PortainerHelmBuilder repo = new PortainerHelmBuilder("1", "rel", "chart", "https://charts.example");
        repo.setValuesSource("repository");
        assertTrue(repo.requiresWorkspace());
    }

    @Test
    void formValidation_valuesPathAndRepo(JenkinsRule jenkins) throws Exception {
        PortainerHelmBuilder.DescriptorImpl d =
                jenkins.getInstance().getDescriptorByType(PortainerHelmBuilder.DescriptorImpl.class);
        FreeStyleProject project = jenkins.createFreeStyleProject();

        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckValuesFilePath("../secret.yaml", "repository", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckValuesFilePath("", "yaml", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckValuesRepositoryUrl("", "repository", project).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckValuesRepositoryUrl("https://u:p@gitlab.example/group/v.git", "repository", project)
                        .kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckValuesRepositoryUrl("", "none", project).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckValues("", "yaml", project).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckValues("", "none", project).kind);
    }

    private void configurePortainer() {
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

    private static boolean isHelmMutateApi(String path, String method) {
        if (path == null) {
            return false;
        }
        if (path.contains("/namespaces")) {
            return true;
        }
        if (path.contains("/kubernetes/helm")
                && ("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
            return true;
        }
        return false;
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
