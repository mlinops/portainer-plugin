# Dev Portainer (local)

Minimal Portainer **2.39.3** for testing the Jenkins Portainer plugin without corporate TLS / `localhost` SAN issues.

## Start

```bash
cd portainer-plugin/examples/dev-portainer
docker compose up -d
```

| URL | Use |
|-----|-----|
| http://localhost:9000 | Browser + Jenkins (simplest) |
| https://localhost:9443 | HTTPS UI/API (self-signed) |
| http://host.docker.internal:9000 | Jenkins running in Docker Desktop |

## First login

1. Open http://localhost:9000 → set admin password.
2. Choose **Get Started** / local Docker environment (socket is mounted).
3. **User menu → My account → Access tokens** → create token.
4. In Jenkins: Credentials → Secret text with that token.
5. **Manage Jenkins → System → Portainer**:
   - URL: `http://host.docker.internal:9000` (Jenkins in Docker) or `http://localhost:9000` (Jenkins on host)
   - API key credential id

## What this validates

| Feature | Local |
|---------|-------|
| API preflight / stacks (Compose via local Docker) | Yes |
| Helm self-call `https://localhost:9443/...` | Yes (default cert) |
| Corporate GitLab chart repo / BeST CA | No — separate |
| Real Kubernetes endpoint | Needs Kind/k3d + Portainer agent (not in this compose) |

## Stop

```bash
docker compose down
# wipe data:
docker compose down -v
```
