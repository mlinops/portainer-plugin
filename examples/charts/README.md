# Portainer Helm chart examples

Minimal `nginx:alpine` Helm chart for packaging into a chart repository used by `portainerHelm`.

| Path | Chart name | Image |
| ---- | ---------- | ----- |
| `nginx/` | `nginx` | `nginx:alpine` |

Portainer installs via chart **repository URL** (`repo`) + chart name — not a Git `manifestFilePath`. Package and serve this chart (e.g. `helm package nginx` + static `index.yaml`), then point `repo` at that URL.

Portainer cannot accept chart-repo HTTP credentials on install; serve the index so Portainer can fetch it without auth (or use a public Package Registry channel).

Hosts: `portainer.example` / `charts.example` / `gitlab.example` only. No real secrets.

## Package locally

```bash
cd examples/charts
helm lint nginx
helm package nginx -d .
# Serve the directory (or upload .tgz + index.yaml) as https://charts.example/helm
helm repo index . --url https://charts.example/helm
```

## Example `portainerHelm` fields

```groovy
portainerHelm(
    endpointId: '2',
    releaseName: 'demo-nginx',
    chart: 'nginx',
    repo: 'https://charts.example/helm',
    namespace: 'default',
    // ensureNamespace: false,  // default true: create NS via Portainer if missing
    // valuesSource: 'none',    // default — omit values (chart defaults)
    valuesSource: 'yaml',
    values: '''
replicaCount: 1
service:
  type: ClusterIP
''',
    // Or fetch values from Git (Jenkins clones; Portainer only gets string values):
    // valuesSource: 'repository',
    // valuesRepositoryUrl: 'https://gitlab.example/group/helm-values.git',
    // valuesFilePath: 'values.yaml',
    // valuesGitCredentialsId: 'git-clone',
    // valuesRepositoryReferenceName: 'refs/heads/main',
    atomic: true
)
```

Full pipeline sample: `../PipelineSyntax.portainerHelm.groovy`.

**Values source:** default **No source** (`valuesSource: 'none'` / omit). **Manual YAML** (`yaml` + `values`). **Repository** (`repository` + Git URL / path / optional creds / ref) — Jenkins shallow-clones (private repos via `GIT_ASKPASS`) and POSTs file content as Portainer `values` (no Git values API). Requires `git` on the agent PATH.

**Ensure namespace:** on by default before install/upgrade/force-reinstall; set `ensureNamespace: false` to skip; skipped under `validateOnly`.