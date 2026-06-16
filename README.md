# MERN CI/CD with Jenkins, Harbor, Docker Swarm and Monitoring

Production-like DevOps lab for a full-stack MERN application. The project demonstrates CI/CD with Jenkins, private image delivery through Harbor, Docker Swarm rolling deployments, security scanning with Trivy, and infrastructure/container monitoring with Prometheus and Grafana.

> This is a personal lab/portfolio project. Some choices are simplified for learning, but the structure follows production-like practices: credentials management, non-root containers, health checks, rolling updates, vulnerability scanning, and rollback-aware deployments.

---

## Architecture

```text
Developer
   |
   v
GitHub
   |
   | webhook / manual build
   v
Jenkins on swarm-manager
   |
   | test -> build -> Trivy scan -> push
   v
Harbor private registry
   |
   | docker stack deploy / docker service update
   v
Docker Swarm
   |
   +-- swarm-manager: Jenkins, Prometheus, Grafana
   |
   +-- swarm-worker: frontend, backend, MongoDB

Monitoring:
  Node Exporter + cAdvisor -> Prometheus -> Grafana
```

### Infrastructure

| VM | Role | Main services |
|---|---|---|
| `swarm-manager` | Swarm manager, CI/CD, monitoring | Jenkins, Prometheus, Grafana |
| `swarm-worker` | App runtime | React frontend, Node backend, MongoDB |
| `harbor-registry` | Private registry | Harbor |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React, Nginx unprivileged |
| Backend | Node.js, Express, Mongoose |
| Database | MongoDB |
| CI/CD | Jenkins Pipeline |
| Registry | Harbor |
| Orchestration | Docker Swarm |
| Security scan | Trivy |
| Monitoring | Prometheus, Grafana, Node Exporter, cAdvisor |
| Cloud | Google Cloud Platform |

---

## Repository Layout

```text
.
├── Jenkinsfile.staging.groovy
├── Jenkinsfile.production.groovy
├── source
│   ├── backend
│   │   ├── Dockerfile
│   │   ├── entrypoint.sh
│   │   ├── server.js
│   │   └── tests
│   ├── frontend
│   │   ├── Dockerfile
│   │   ├── nginx.conf
│   │   └── src
│   ├── docker-compose.staging.yml
│   ├── docker-compose.production.yml
│   ├── docker-compose.monitoring.yml
│   └── monitoring
│       ├── prometheus/prometheus.yml
│       └── grafana/provisioning/datasources/prometheus.yml
└── README.md
```

---

## Environments

| Item | Staging | Production |
|---|---|---|
| Branch | `staging` | `main` |
| Jenkinsfile | `Jenkinsfile.staging.groovy` | `Jenkinsfile.production.groovy` |
| Stack | `mern-staging` | `mern-production` |
| Frontend port | `81` | `80` |
| Backend port | `5001` | `5000` |
| Replicas | 1 | 2 |
| Trivy policy | Report HIGH/CRITICAL | Fail on HIGH/CRITICAL |
| Deployment mode | Manual action | Automatic pipeline |

---

## Application Containers

### Backend

The backend image uses a multi-stage Dockerfile:

- `deps` stage installs production dependencies with `npm ci --omit=dev`.
- `runtime` stage copies only runtime files.
- Runtime uses a non-root user.
- Healthcheck uses `127.0.0.1` to avoid `localhost` resolution issues.
- `npm` and `npx` are removed from runtime to reduce scan surface.

### Frontend

The frontend image uses:

- React build stage.
- `nginxinc/nginx-unprivileged`.
- Nginx listens on port `8080`.
- Healthcheck uses `127.0.0.1:8080`.

---

## Jenkins Pipelines

### Staging Pipeline

Actions:

| Action | Meaning |
|---|---|
| `up_code` | Test, build, scan, push, deploy selected service |
| `start` | Deploy stack using the `staging` tag |
| `stop` | Remove staging stack |
| `rollback` | Redeploy selected image tag |
| `cleanup` | Prune Docker build cache and dangling images older than 24h |

`BUILD_SERVICES` supports:

```text
all
backend
frontend
```

When only one service is selected, the pipeline uses `docker service update --with-registry-auth` so the other service is not accidentally moved to the new build tag.

### Production Pipeline

Production flow:

```text
Checkout -> Test -> Build Backend -> Build Frontend -> Security Scan -> Push to Harbor -> Deploy -> Health Check
```

Production fails when:

- Backend tests fail.
- Frontend tests fail.
- Docker build fails.
- Trivy finds HIGH/CRITICAL vulnerabilities.
- Health check fails after retries.

---

## Jenkins Requirements

### Credentials

| ID | Type | Usage |
|---|---|---|
| `harbor-credentials` | Username/password | Docker login to Harbor |

### Tools

Install Jenkins NodeJS plugin and configure:

```text
Manage Jenkins -> Tools -> NodeJS installations
Name: nodejs-18
Version: NodeJS 18.x
```

Jenkinsfile uses:

```groovy
NODEJS_TOOL = env.NODEJS_TOOL ?: 'nodejs-18'
```

### Docker access

Jenkins must be able to access Docker on the Swarm manager.

Check from the host:

```bash
docker exec -u jenkins $(docker ps -qf "name=jenkins") sh -lc 'id; ls -ln /var/run/docker.sock; docker version'
```

Expected:

- `jenkins` belongs to the group ID that owns `/var/run/docker.sock`.
- `docker version` works without `permission denied`.

---

## Secrets

Backend environment is mounted as Docker Secret:

```yaml
secrets:
  - source: backend_env_production
    target: backend_env
```

The backend entrypoint loads `/run/secrets/backend_env` into environment variables.

Create secrets on the Swarm manager before deployment.

Example for staging:

```bash
docker secret create backend_env_staging /path/to/backend.env.staging
```

Example for production:

```bash
docker secret create backend_env_production /path/to/backend.env.production
```

Grafana admin password is also stored as a Docker Secret:

```bash
printf 'change-this-password' | docker secret create grafana_admin_password -
```

Do not commit real secret values to Git.

---

## Monitoring

Monitoring stack:

| Service | Purpose | Port |
|---|---|---|
| Prometheus | Metrics database and scraper | `9090` |
| Grafana | Dashboard UI | `3000` |
| Node Exporter | Host CPU/RAM/disk/network metrics | internal `9100` |
| cAdvisor | Container CPU/RAM/network/restart metrics | internal `8080` |

Prometheus uses Docker Swarm DNS:

```yaml
tasks.monitoring_node-exporter:9100
tasks.monitoring_cadvisor:8080
```

Grafana datasource is provisioned automatically:

```text
Prometheus -> http://monitoring_prometheus:9090
```

### Deploy Monitoring Stack

Run on `swarm-manager`.

Check first:

```bash
docker node ls
docker secret ls
ls -la source/docker-compose.monitoring.yml
ls -la source/monitoring/prometheus/prometheus.yml
```

Create Grafana secret if it does not exist:

```bash
printf 'change-this-password' | docker secret create grafana_admin_password -
```

Deploy:

```bash
cd source
docker stack deploy -c docker-compose.monitoring.yml monitoring
```

Verify:

```bash
docker stack services monitoring
docker service ps monitoring_prometheus --no-trunc
docker service ps monitoring_grafana --no-trunc
```

Open:

```text
Prometheus: http://<manager-ip>:9090
Grafana:    http://<manager-ip>:3000
```

### Suggested Grafana Dashboards

Import dashboards from Grafana.com:

| Dashboard | Purpose |
|---|---|
| Node Exporter Full | Host CPU, RAM, disk, network |
| cAdvisor / Docker containers | Container CPU, memory, network |

Useful PromQL examples:

```promql
rate(container_cpu_usage_seconds_total[5m])
container_memory_usage_bytes
rate(container_network_receive_bytes_total[5m])
node_filesystem_avail_bytes
node_memory_MemAvailable_bytes
```

---

## Deployment Commands

### Staging

Normally use Jenkins `mern-staging` job.

Manual deploy example:

```bash
cd source
export HARBOR_HOST=34.21.141.11
export IMAGE_TAG=staging
docker stack deploy --with-registry-auth -c docker-compose.staging.yml mern-staging
```

### Production

Normally use Jenkins `mern-production` job.

Manual deploy example:

```bash
cd source
export HARBOR_HOST=34.21.141.11
export IMAGE_TAG=production
docker stack deploy --with-registry-auth -c docker-compose.production.yml mern-production
```

---

## Rollback

### Docker Swarm service rollback

Check service history:

```bash
docker service ps mern-production_backend --no-trunc
docker service ps mern-production_frontend --no-trunc
```

Rollback:

```bash
docker service rollback mern-production_backend
docker service rollback mern-production_frontend
```

Verify:

```bash
docker stack services mern-production
curl -f http://<worker-ip>:5000/health
curl -f http://<worker-ip>/
```

### Staging rollback by tag

Use Jenkins staging action:

```text
ACTION=rollback
ROLLBACK_VERSION=<build-number>
```

---

## Troubleshooting

### Swarm service is `0/1`

```bash
docker stack services <stack>
docker service ps <service> --no-trunc
docker service logs <service> --tail 100
```

If task is `Complete` and logs show Nginx received `SIGQUIT`, inspect healthcheck:

```bash
docker inspect <container_id> --format '{{json .State.Health}}'
```

### Jenkins cannot run npm

Symptom:

```text
npm: not found
```

Fix:

- Install Jenkins NodeJS plugin.
- Configure `nodejs-18`.
- Ensure Jenkinsfile uses NodeJS tool wrapper.

### `npm ci` fails

Symptom:

```text
package.json and package-lock.json are not in sync
```

Fix:

- Update `package-lock.json`.
- Pin dependency versions when needed.
- Re-run `npm ci` locally before pushing.

### Jenkins cannot build Docker image

Symptom:

```text
permission denied while trying to connect to the docker API at unix:///var/run/docker.sock
```

Check:

```bash
docker exec -u jenkins $(docker ps -qf "name=jenkins") sh -lc 'id; ls -ln /var/run/docker.sock; docker version'
```

Fix the group ID mapping between Jenkins container user and Docker socket group.

### Trivy blocks production

Symptom:

```text
Total: ... (HIGH: ..., CRITICAL: ...)
```

Fix path:

- Update base image.
- Run OS package upgrade in image build.
- Remove unused runtime tools.
- Use multi-stage runtime images.

---

## Security Considerations

- Jenkins uses Credentials for Harbor login.
- Docker Swarm uses `--with-registry-auth` for private image pulls.
- Backend environment is stored in Docker Secrets.
- Grafana password is stored in Docker Secret.
- Containers run as non-root users where possible.
- Frontend uses unprivileged Nginx.
- Trivy blocks production on HIGH/CRITICAL vulnerabilities.
- Docker socket access is powerful and must be restricted.

Lab-only warning:

```text
Do not use chmod 666 /var/run/docker.sock in real production.
```

---

## Current Completion Checklist

| Item | Status |
|---|---|
| MERN source code | Done |
| Dockerfiles | Done |
| Staging stack | Done |
| Production stack | Done |
| Jenkins staging pipeline | Done |
| Jenkins production pipeline | Done |
| Harbor image push | Done |
| Trivy scan | Done |
| Health checks | Done |
| Docker Secrets | Done |
| Monitoring stack files | Done |
| README portfolio docs | Done |
| Grafana dashboard import | Manual next step |
