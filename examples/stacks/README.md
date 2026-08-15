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
    namespace: 'default',
    // ensureNamespace: false,  // default true: create NS via Portainer if missing
    repositoryUrl: 'https://gitlab.example/group/manifests.git',
    manifestFilePath: 'examples/stacks/kubernetes-manifest.yaml',
    repositoryReferenceName: 'refs/heads/main',
    gitCredentialsId: 'git-clone'
)
```

Full pipeline sample (including Manual YAML): `../PipelineSyntax.portainerManifest.groovy`.

**Ensure namespace:** on by default (Freestyle Advanced → **Ensure namespace** / Pipeline omit or `ensureNamespace: true`). Calls Portainer `GET/POST /api/kubernetes/{endpointId}/namespaces…` before deploy. Existing NS is a no-op; missing NS is created; HTTP 409 (race) is treated as ready. Requires cluster create-namespace permission. Set `ensureNamespace: false` to skip. Not run when `validateOnly: true` (logs `would ensure namespace=…` only).