# Portainer plugin — screenshot checklist

Capture real Jenkins UI screenshots for the selling README. Do **not** commit placeholder or synthetic images.

## Required (hosting)

| File | Where to capture | Notes |
|------|------------------|-------|
| `config.png` | **Manage Jenkins → System → Portainer** | Global URL + API key credentials; use `https://portainer.example:9443` (or similar `*.example` host) |
| `build.png` | **Job configure → Portainer Stack Deployment** | Freestyle build step with Inherit connection; show typical stack fields |

## Optional (step forms)

| File | Where to capture |
|------|------------------|
| `step-helm.png` | Job configure → Portainer Helm Deployment |
| `step-manifest.png` | Job configure → Portainer Manifest Deployment |
| `step-config.png` | Job configure → Portainer Stack Config |
| `step-secret.png` | Job configure → Portainer Stack Secret |

## Before capture

1. Install the plugin HPI on a local Jenkins (JDK 21, baseline 2.541+).
2. Set Portainer URL to a `*.example` host (never a real production hostname).
3. Use dummy credential labels; never show real API tokens or Vault secrets.
4. Crop to the relevant form section; keep Jenkins chrome readable.

## After capture

1. Save PNGs in this directory (`docs/images/`).
2. In the root `README.md` **Screenshots** section, replace the pending note with live embeds:

```markdown
![Manage Jenkins → System → Portainer](docs/images/config.png)

![Job configure → Portainer Stack Deployment](docs/images/build.png)
```

3. Optionally embed the step-form PNGs listed above.

## Related

- Backlog: PORT-69 (`Tasks/Backlog/PORT-69-readme-screenshots.md` in the development workspace)
