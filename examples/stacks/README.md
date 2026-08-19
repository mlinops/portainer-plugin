# Portainer stack / manifest examples

Minimal `nginx:alpine` fixtures for pointing Portainer `composeFilePath` / `manifestFilePath` at during local / CI tests.

| File | Step / field | Runtime |
| ---- | ------------ | ------- |
| `docker-compose.yml` | `portainerStack` · `stackType: compose` | Docker Compose (standalone endpoint) |
| `docker-stack-external-configs.yml` | `portainerStackConfig` + `portainerStackSecret` + `portainerStack` | Swarm external configs/secrets (`${RABBITMQ_CONFIG}`, `${RABBITMQ_SIGNING_KEY}`) |
| `kubernetes-manifest.yaml` | `portainerManifest` · `manifestFilePath` | Kubernetes (endpoint type 5/6/7) |

Compose/Swarm publish **host 8080 → container 80** so they do not fight Jenkins on port 80. The Kubernetes example is a Deployment + ClusterIP Service on port 80.

Helm chart fixture (separate tree): [`../charts/nginx`](../charts/nginx) — see [`../charts/README.md`](../charts/README.md).

Hosts/credentials in docs: fictional only (`portainer.example`, `gitlab.example`, `charts.example`). No real secrets.

## Example `portainerStack` fields

Instance URL/token come from **Manage Jenkins → System → Portainer** (no `instanceId` / `action` in the step). Freestyle step label: **Portainer Stack Deployment**; Pipeline symbol: `portainerStack`. The step deploys if the stack is missing, otherwise redeploys.

**Compose (standalone):**

```groovy
portainerStack(
    endpointId: '1',
    stackType: 'compose',
    stackName: 'nginx-demo',
    repositoryUrl: 'https://gitlab.example/group/stack.git',
    composeFilePath: 'examples/stacks/docker-compose.yml',
    repositoryReferenceName: 'refs/heads/main',
    gitCredentialsId: 'git-clone'
)
```

**Swarm:**

```groovy
portainerStack(
    endpointId: '1',
    stackType: 'swarm',
    stackName: 'nginx-demo',
    repositoryUrl: 'https://gitlab.example/group/stack.git',
    composeFilePath: 'examples/stacks/docker-stack.yml',
    repositoryReferenceName: 'refs/heads/main',
    gitCredentialsId: 'git-clone'
)
```

Adjust `composeFilePath` to the path of this file inside the Git repository Portainer clones. Full pipeline sample: `../PipelineSyntax.portainerStack.groovy`.

## Example `portainerManifest` fields

Kubernetes environment only. Freestyle step: **Portainer Manifest Deployment**; Pipeline symbol: `portainerManifest`. Upsert: create if missing, update if present.

```groovy
portainerManifest(
    endpointId: '2',
    stackName: 'nginx-demo',
    repositoryUrl: 'https://gitlab.example/group/manifests.git',
    manifestFilePath: 'examples/stacks/kubernetes-manifest.yaml',
    repositoryReferenceName: 'refs/heads/main',
    gitCredentialsId: 'git-clone'
)
```

Full pipeline sample: `../PipelineSyntax.portainerManifest.groovy`.

**Namespace:** set in the manifest file (`metadata.namespace` or `kind: Namespace`). The step has no Namespace field and does not send one to Portainer. If Portainer stack metadata exists but live Kubernetes resources do not, the build fails; remove the stale stack in Portainer and retry.