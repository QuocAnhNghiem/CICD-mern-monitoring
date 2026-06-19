# Demo Troubleshooting Notes - MERN CI/CD Jenkins, Harbor, Docker Swarm

File nay ghi lai cac loi da gap trong qua trinh demo, cach nhan dien, nguyen nhan va cach fix. Muc tieu la lan sau gap lai co the debug nhanh hon, dong thoi co noi dung de bo sung vao README/portfolio.

---

## 1. Frontend service trong Swarm bi `0/1`, task `Complete`

### Dau hieu

```bash
docker stack services mern-staging
docker service ps mern-staging_frontend --no-trunc
```

Ket qua thay:

```text
mern-staging_frontend replicated 0/1
CURRENT STATE: Complete
ERROR: rong
```

Log service:

```bash
docker service logs mern-staging_frontend --tail 100
```

Thay Nginx start binh thuong, sau do nhan `SIGQUIT` va shutdown.

### Nguyen nhan

Container khong crash. Swarm stop container vi healthcheck fail:

```text
wget: can't connect to remote host: Connection refused
```

Healthcheck dung:

```yaml
http://localhost:8080/
```

Trong image Nginx, `localhost` co the resolve khong dung endpoint ma Nginx dang listen. Khi doi sang `127.0.0.1` thi tra HTML binh thuong.

### Cach fix

Trong `source/docker-compose.staging.yml` va `source/docker-compose.production.yml`, doi healthcheck:

```yaml
healthcheck:
  test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/"]
```

Backend healthcheck cung doi tu `localhost` sang:

```yaml
http://127.0.0.1:5000/health
```

Trong `source/backend/Dockerfile` cung doi:

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:5000/health || exit 1
```

### Bai hoc

- Neu `docker service ps` hien `Complete` va `ERROR` rong, hay nghi toi healthcheck/SIGTERM/SIGQUIT, khong chi nghi image pull fail.
- Nen test healthcheck ben trong container:

```bash
docker inspect <container_id> --format '{{json .State.Health}}'
```

---

## 2. Jenkins stage Test loi `npm: not found`

### Dau hieu

Trong Jenkins:

```text
+ npm ci
/var/jenkins_home/.../script.sh.copy: 1: npm: not found
```

### Nguyen nhan

Jenkins agent/container khong co Node.js/npm trong `PATH`.

### Cach fix

Cai Jenkins plugin:

```text
NodeJS
```

Sau do vao:

```text
Manage Jenkins -> Tools -> NodeJS installations
```

Tao tool:

```text
nodejs-18
```

Trong Jenkinsfile, boc cac lenh npm bang NodeJS tool:

```groovy
NODEJS_TOOL = env.NODEJS_TOOL ?: 'nodejs-18'

def withNodeTool(Closure body) {
    def nodeHome = tool name: NODEJS_TOOL, type: 'jenkins.plugins.nodejs.tools.NodeJSInstallation'
    withEnv(["PATH=${nodeHome}/bin:${env.PATH}"]) {
        body()
    }
}
```

Dung trong test:

```groovy
withNodeTool {
    dir('source/frontend') {
        sh 'npm ci'
        sh 'CI=true npm test'
    }
}
```

### Bai hoc

- Jenkinsfile khong nen gia dinh server da cai san tool.
- Nen khai bao tool ro rang bang Jenkins Tool Configuration hoac chay qua Docker image build agent.

---

## 3. Frontend `npm ci` loi package-lock khong dong bo

### Dau hieu

```text
npm ci can only install packages when your package.json and package-lock.json are in sync
Invalid: lock file's typescript@6.0.3 does not satisfy typescript@4.9.5
```

### Nguyen nhan

`package-lock.json` dang lock `typescript@6.0.3`, nhung `react-scripts@5.0.1` chi tuong thich voi TypeScript `^3.2.1 || ^4`.

### Cach fix

Pin TypeScript trong `source/frontend/package.json`:

```json
"devDependencies": {
  "typescript": "4.9.5"
}
```

Cap nhat `source/frontend/package-lock.json` de `node_modules/typescript` la:

```text
typescript@4.9.5
```

Sau do verify:

```bash
npm ci --prefix source/frontend
CI=true npm test --prefix source/frontend
npm run build:staging --prefix source/frontend
```

### Bai hoc

- Khi da dung CI, nen dung `npm ci` thay vi `npm install` de bat loi lockfile som.
- Neu `npm ci` fail, khong nen sua workaround trong Jenkinsfile. Nen sua `package.json` va `package-lock.json` cho dong bo.

---

## 4. Jenkins production loi Docker socket permission

### Dau hieu

Production fail o stage Build Backend:

```text
permission denied while trying to connect to the docker API at unix:///var/run/docker.sock
```

Loi phu:

```text
Can't add file ... .dockerignore to tar: io: read/write on closed pipe
```

### Nguyen nhan

Jenkins container co Docker CLI nhung user `jenkins` khong co quyen ghi vao Docker socket cua host:

```text
/var/run/docker.sock
```

### Lenh kiem tra

Chay tren VM host:

```bash
docker exec -u jenkins $(docker ps -qf "name=jenkins") sh -lc 'id; ls -ln /var/run/docker.sock; docker version'
```

Neu thay `permission denied`, user `jenkins` chua nam trong group co GID trung voi Docker socket.

### Cach fix da dung trong lab

Lay GID cua group `docker` tren host:

```bash
DOCKER_GID=$(getent group docker | cut -d: -f3)
```

Tao group trong Jenkins container voi cung GID va add user `jenkins` vao group:

```bash
docker exec -u root $(docker ps -qf "name=jenkins") \
  bash -c "groupadd -g ${DOCKER_GID} docker_host 2>/dev/null || true && usermod -aG docker_host jenkins"
```

Restart Jenkins container de session group moi co hieu luc:

```bash
docker restart $(docker ps -qf "name=jenkins")
```

Kiem tra lai:

```bash
docker exec -u jenkins $(docker ps -qf "name=jenkins") sh -lc 'id; ls -ln /var/run/docker.sock; docker version'
```

### Giai thich `docker` va `docker_host`

Tren host co the co:

```text
docker:x:988:...
```

Trong container co the tao:

```text
docker_host:x:988:jenkins
```

Ten group khac nhau khong sao. Linux permission quan tam GID so, o day la `988`.

### Bai hoc

- Mount Docker socket vao Jenkins container thi phai xu ly group permission.
- `chmod 666 /var/run/docker.sock` co the unblock nhanh trong lab, nhung khong production-like vi bat ky user nao cung co the dieu khien Docker daemon.

---

## 5. Chon `up_code frontend` nhung backend cung bi keo sang tag moi

### Dau hieu

Khi chon build moi frontend, service backend cung bi deploy sang cung `IMAGE_TAG`.

Vi du:

```text
frontend: 34.21.141.11/mern/frontend:9
backend : 34.21.141.11/mern/backend:9
```

Trong khi chi frontend vua duoc build tag `9`.

### Nguyen nhan

Pipeline staging build theo service duoc chon, nhung deploy lai chay:

```bash
docker stack deploy -c source/docker-compose.staging.yml mern-staging
```

voi mot bien chung:

```text
IMAGE_TAG=<BUILD_NUMBER>
```

Stack deploy ap dung tag do cho ca frontend va backend.

### Cach fix

Neu `BUILD_SERVICES == all`, deploy ca stack:

```groovy
deployStack(COMPOSE_FILE, STACK_NAME, buildTag)
```

Neu chi build backend/frontend, update dung service:

```groovy
docker service update --with-registry-auth \
  --image ${imageName}:${imageTag} \
  ${stackName}_${serviceName}
```

### Bai hoc

- Neu pipeline cho phep deploy tung service, deploy logic cung phai tung service.
- Dung mot `IMAGE_TAG` chung cho ca stack chi an toan khi build ca stack.

---

## 6. Health check Jenkins chi warning, pipeline van success

### Dau hieu

App healthcheck fail nhung Jenkins job van co the hien success.

### Nguyen nhan

Ham healthcheck cu chi:

```groovy
echo "health check failed"
```

ma khong `error(...)`.

### Cach fix

Them retry va fail that neu het retry:

```groovy
def healthCheck(String url, String name) {
    int maxAttempts = 10
    int waitSeconds = 6

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        def result = sh(script: "curl -sf ${url}", returnStatus: true)
        if (result == 0) {
            echo "✅ ${name} is healthy!"
            return
        }
        echo "⚠️ ${name} health check failed (${attempt}/${maxAttempts})"
        if (attempt < maxAttempts) {
            sleep(waitSeconds)
        }
    }

    error("❌ ${name} health check failed after ${maxAttempts} attempts: ${url}")
}
```

### Bai hoc

- Health check la gate cuoi cung cua deploy. Fail thi pipeline phai fail.
- Can retry vi Swarm rolling update can thoi gian de service len.

---

## 7. Trivy scan bi bo qua do `|| true`

### Dau hieu

Trivy scan co the bao HIGH/CRITICAL nhung pipeline van tiep tuc deploy.

### Nguyen nhan

Lenh scan cu co:

```bash
trivy image ... || true
```

### Cach fix

Production nen fail khi co HIGH/CRITICAL:

```bash
trivy image --severity HIGH,CRITICAL --exit-code 1 --no-progress <image>
```

Staging co the chi warning de tien debug:

```bash
trivy image --severity HIGH,CRITICAL --exit-code 0 --no-progress <image>
```

### Bai hoc

- Staging co the linh hoat, production nen co security gate that.
- `|| true` chi nen dung khi minh co chu dich ro rang.

---

## 8. `docker system prune -f` trong pipeline

### Van de

Pipeline cu chay:

```bash
docker system prune -f
```

sau moi build.

### Rủi ro

Lenh nay xoa stopped containers, unused networks, dangling images va build cache. No co the lam mat du lieu debug hoac image can cho rollback/local inspect.

### Cach fix

Khong tu dong prune sau moi pipeline. Staging co action rieng `cleanup`:

```groovy
sh 'docker builder prune -f --filter until=24h || true'
sh 'docker image prune -f --filter until=24h || true'
```

### Bai hoc

- Cleanup nen la action rieng, khong nen luon chay trong `finally`.
- Neu can cleanup tu dong, nen co filter tuoi doi tuong va chinh sach giu lai N build gan nhat.

---

## 9. `env-cmd` co trong package.json nhung lockfile khong co

### Dau hieu

Local build hoac Docker build co the fail:

```text
env-cmd: not found
```

### Nguyen nhan

`package.json` co script dung `env-cmd`, nhung dependency/lockfile khong dong bo.

### Cach fix da dung

Bo `env-cmd`, dung shell co san de load env file:

```json
"build:staging": "set -a && . ./.env.staging && set +a && react-scripts build",
"build:production": "set -a && . ./.env.production && set +a && react-scripts build"
```

### Bai hoc

- Neu them package moi, phai update `package-lock.json`.
- Trong Linux CI, voi env file don gian, co the dung shell built-in de giam dependency.

---

## 10. Production bi chan o Trivy scan vi HIGH/CRITICAL CVE

### Dau hieu

Production fail o stage Security Scan:

```text
trivy image --severity HIGH,CRITICAL --exit-code 1 ...
Total: 17 (HIGH: 15, CRITICAL: 2)
PRODUCTION PIPELINE FAILED
```

### Nguyen nhan

Production pipeline duoc cau hinh dung:

```bash
--exit-code 1
```

nen khi Trivy thay HIGH/CRITICAL vulnerabilities thi job fail. Loi thuong den tu:

```text
Base OS packages: openssl/libcrypto/libssl, musl, zlib
Node/npm packages bundled inside base image
```

### Cach fix da chon

Dung backend Dockerfile multi-stage runtime:

```dockerfile
FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev

FROM node:22-alpine AS runtime
RUN apk upgrade --no-cache \
    && addgroup -g 1001 -S appgroup \
    && adduser -u 1001 -S appuser -G appgroup
WORKDIR /app
COPY --from=deps --chown=appuser:appgroup /app/node_modules ./node_modules
COPY --chown=appuser:appgroup package*.json ./
COPY --chown=appuser:appgroup server.js entrypoint.sh ./
RUN chmod +x /app/entrypoint.sh
USER appuser
```

### Bai hoc

- Production security scan fail la hanh vi dung, khong phai loi pipeline.
- Nen update base image va OS packages truoc khi ha chuan scan.
- Multi-stage giup tach buoc cai dependency va runtime, lam image gon va de harden hon.

---

## Checklist debug nhanh lan sau

### Khi Swarm service khong len

```bash
docker stack services <stack>
docker service ps <service> --no-trunc
docker service logs <service> --tail 100
docker inspect <container_id> --format '{{json .State.Health}}'
```

Doc theo thu tu:

```text
Image pull fail?
Container crash?
Container Complete?
Healthcheck unhealthy?
Port/secret/env co sai khong?
```

### Khi Jenkins fail o npm

```bash
node -v
npm -v
npm ci
```

Neu Jenkins container khong co npm, dung Jenkins NodeJS Plugin hoac Docker node image.

### Khi Jenkins fail o docker build

```bash
docker exec -u jenkins $(docker ps -qf "name=jenkins") sh -lc 'id; ls -ln /var/run/docker.sock; docker version'
```

Neu permission denied, kiem tra GID cua socket va group trong container.

### Khi Harbor/private registry co van de

```bash
docker login <harbor-host>
docker pull <harbor-host>/mern/frontend:<tag>
docker pull <harbor-host>/mern/backend:<tag>
docker service ps <service> --no-trunc
```

Neu Swarm worker pull private image, deploy can:

```bash
docker stack deploy --with-registry-auth ...
docker service update --with-registry-auth ...
```

---

## Ghi chu production-like

- Khong dung `chmod 666 /var/run/docker.sock` cho production that.
- Khong hard-code secret vao Jenkinsfile, Dockerfile, compose hoac source.
- Docker socket gan nhu quyen root tren host, can han che ai co quyen truy cap.
- Production pipeline nen fail khi test, build, scan hoac healthcheck fail.
- Can bo sung README, monitoring stack, rollback guide va security considerations de project thuyet phuc hon khi dua vao portfolio.

---

## 10. Nginx non-root: PID file Permission Denied

### Dau hieu

```text
nginx: [emerg] open() "/run/nginx.pid" failed (13: Permission denied)
```

Frontend container crash loop, service luon 0/1.

### Nguyen nhan

Dockerfile dung `USER appuser` (non-root), nhung Nginx mac dinh:
- Ghi PID file vao `/run/nginx.pid` (can root)
- Co directive `user nginx;` trong nginx.conf chinh (can root de chuyen user)

### Cach fix

**Phuong an 1 (don gian nhat):** Dung image `nginxinc/nginx-unprivileged:alpine`:

```dockerfile
FROM nginxinc/nginx-unprivileged:alpine
COPY --from=build-stage /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
```

**Phuong an 2 (thu cong):** Dung `nginx:alpine` nhung sua PID va user:

```dockerfile
RUN sed -i 's|/run/nginx.pid|/tmp/nginx.pid|g' /etc/nginx/nginx.conf && \
    sed -i '/^user /d' /etc/nginx/nginx.conf && \
    chown -R appuser:appgroup /app /var/cache/nginx /var/log/nginx /tmp
```

### Bai hoc

- Khi chay Nginx non-root, phai xu ly PID file va user directive.
- `nginx-unprivileged` lam san tat ca, giam tu 20 dong Dockerfile xuong 4 dong.
- Day la loi rat pho bien khi hardening Docker image.

---

## 11. Swarm Worker khong pull duoc image tu Harbor (private registry)

### Dau hieu

```bash
docker service ps mern-staging_frontend --no-trunc
```

Ket qua:

```text
ERROR: "No such image: 34.21.141.11/mern/frontend:6"
```

Manager build + push OK, nhung Worker khong pull duoc.

### Nguyen nhan

Harbor dung HTTPS self-signed certificate. Manager da co cert, nhung **Worker chua co**.

### Cach fix

SSH vao **swarm-worker** va copy cert:

```bash
# Tao thu muc cert cho Harbor
sudo mkdir -p /etc/docker/certs.d/34.21.141.11

# Copy cert tu Harbor VM
sudo scp user@harbor-ip:/path/to/harbor/cert/ca.crt /etc/docker/certs.d/34.21.141.11/ca.crt

# Restart Docker
sudo systemctl restart docker

# Login Harbor
docker login 34.21.141.11 -u admin -p <password>
```

### Bai hoc

- Moi node trong Swarm can pull image deu phai co cert cua private registry.
- Nen lam buoc nay **ngay khi join node vao Swarm**, khong doi den luc deploy.
- Nen ghi vao checklist setup node moi.

---

## 12. `docker stack deploy` thieu `--with-registry-auth`

### Dau hieu

Worker da co cert, `docker pull` tay thanh cong, nhung Swarm service van fail:

```text
ERROR: "No such image: ..."
```

### Nguyen nhan

`docker stack deploy` **mac dinh khong gui credential** tu Manager sang Worker. Worker co cert nhung **khong co login token**.

### Cach fix

Them `--with-registry-auth`:

```bash
# Deploy stack
docker stack deploy --with-registry-auth -c docker-compose.yml mern-staging

# Hoac update service
docker service update --with-registry-auth --image <image>:<tag> <service>
```

Trong Jenkinsfile, login Harbor truoc roi deploy:

```groovy
withCredentials([usernamePassword(credentialsId: HARBOR_CREDENTIAL, ...)]) {
    sh """
        echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
        docker stack deploy --with-registry-auth -c ${composeFile} ${stackName}
    """
}
```

### Bai hoc

- Private registry + Swarm = **bat buoc** `--with-registry-auth`.
- Docker Hub public thi khong can, nhung Harbor/ECR/GCR/ACR deu can.
- Loi nay rat kho debug vi `docker pull` tay thanh cong nhung Swarm van fail.

---

## 13. Active Choices Groovy script bi Jenkins sandbox chan

### Dau hieu

Khi chon `rollback` trong Jenkins UI, dropdown ROLLBACK_VERSION hien:

```text
Scripts not permitted to use staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods execute java.util.List
```

### Nguyen nhan

Active Choices Groovy script dung `['docker', 'images', ...].execute()` de doc danh sach image tag. Jenkins sandbox khong cho phep chay lenh he thong.

### Cach fix

Vao **Manage Jenkins** → **In-process Script Approval** → Tim dong dang cho duyet → Bam **Approve**.

### Bai hoc

- Jenkins sandbox chan cac lenh nguy hiem nhu `execute()`, `Runtime.exec()`.
- Moi khi thay loi `Scripts not permitted`, vao Script Approval de duyet.
- Trong production that, nen review ky script truoc khi approve.
- Co the chay sandbox mode = false trong Active Choices nhung giam bao mat.

---

## 14. Docker Secret + entrypoint.sh pattern

### Van de

Backend can env vars (MONGO_URI, NODE_ENV, CLIENT_URL...) nhung Docker Secret chi mount thanh **file** o `/run/secrets/`, **khong tu dong** thanh env vars.

### Luong hoat dong

```text
Docker Secret (file) → entrypoint.sh (doc file, export) → Node.js (doc process.env)
```

### Cach implement

`entrypoint.sh` (phuong phap an toan):

```bash
#!/bin/sh
if [ -f /run/secrets/backend_env ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|\#*) continue ;;
            *=*) export "$line" ;;
        esac
    done < /run/secrets/backend_env
fi
exec "$@"
```

Dockerfile:

```dockerfile
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["node", "server.js"]
```

docker-compose:

```yaml
secrets:
  - source: backend_env_staging
    target: backend_env

secrets:
  backend_env_staging:
    external: true
```

Tao secret tren Swarm Manager:

```bash
echo -e "NODE_ENV=staging\nPORT=5000\nMONGO_URI=mongodb://db:27017/mydb" | docker secret create backend_env_staging -
```

### Cach KHONG nen dung

```bash
# Dung xargs - de loi voi ky tu dac biet, dau cach, quotes
export $(cat /run/secrets/backend_env | xargs)
```

### Bai hoc

- Docker Secret != env vars. Can entrypoint script lam cau noi.
- Parse bang `while read` an toan hon `xargs`.
- Luu y: secret uid/gid phai match voi user trong container (vd: uid=1001).
- Nen test thu: `docker secret create test - <<< "KEY=value with spaces"` de dam bao parse dung.

---

## 15. VM restart lam thay doi IP, mat Docker socket permission

### Dau hieu

Sau khi stop/start VM tren GCP:
- Jenkins URL doi IP
- Pipeline fail voi `permission denied ... docker.sock`
- Webhook GitHub khong gui duoc (IP cu)

### Nguyen nhan

- GCP VM dung IP ephemeral (dong) → restart = IP moi
- Docker socket permission bi reset ve mac dinh

### Checklist sau khi restart VM

```text
[ ] Kiem tra IP moi: gcloud compute instances list
[ ] Cap nhat Jenkins URL trong GitHub Webhook
[ ] Cap nhat IP trong Jenkinsfile neu can (HARBOR_HOST, WORKER_IP)
[ ] Fix Docker socket permission trong Jenkins container
[ ] Kiem tra Swarm: docker node ls
[ ] Kiem tra Harbor: docker login <harbor-ip>
[ ] Deploy lai stack neu da bi rm truoc khi stop
```

### Cach tranh

- Dung **Static IP** (GCP: gcloud compute addresses create)
- Hoac dung domain name thay vi IP truc tiep

### Bai hoc

- Luon dung Static IP cho lab de tranh phai update nhieu cho.
- Ghi checklist restart de khong quen buoc nao.
- Nen stop stack (`docker stack rm`) truoc khi stop VM de tranh corrupt data.

---

## 16. cAdvisor Docker 29 overlayfs mat container labels

### Dau hieu

cAdvisor van chay, Prometheus van scrape duoc endpoint `/metrics`, nhung container metrics chi con root cgroup:

```bash
curl -s http://127.0.0.1:8081/metrics | grep "^container_cpu_usage_seconds_total" | head -5
curl -s http://127.0.0.1:8081/metrics | grep "^container_memory_working_set_bytes" | head -5
```

Ket qua:

```text
container_cpu_usage_seconds_total{cpu="total",id="/"} ...
container_memory_working_set_bytes{id="/"} ...
```

Kiem tra labels khong thay gi:

```bash
curl -s http://127.0.0.1:8081/metrics | grep "container_label_" | head -10
```

Log cAdvisor co loi:

```text
Failed to create existing container: /system.slice/docker-<container-id>.scope
failed to identify the read-write layer ID for container "<container-id>"
open /rootfs/var/lib/docker/image/overlayfs/layerdb/mounts/<container-id>/mount-id: no such file or directory
```

### Nguyen nhan

Moi truong gap loi:

```text
Docker Engine: 29.5.3
Storage Driver: overlayfs
cAdvisor: v0.49.x / v0.52.1 / zcube/cadvisor deu bi
```

Trong source code cAdvisor, khi tao Docker container handler, cAdvisor goi ham doc `mount-id` trong:

```text
/var/lib/docker/image/overlayfs/layerdb/mounts/<container-id>/mount-id
```

Tren Docker 29.5.3 + overlayfs, thu muc/file nay khong con ton tai theo layout cu. Khi cAdvisor khong doc duoc `mount-id`, Docker handler cua container bi huy. Hau qua:

```text
Docker handler fail
-> cAdvisor fallback thanh system/root cgroup
-> metrics chi con id="/"
-> mat name/image/container_label_*
-> Grafana khong group duoc theo Swarm service
```

### Nhung cach da thu nhung khong du

Da thu cac cach sau nhung khong fix duoc nguyen nhan goc:

```yaml
image: gcr.io/cadvisor/cadvisor:v0.49.2
image: gcr.io/cadvisor/cadvisor:v0.52.1
image: zcube/cadvisor:latest
```

Da thu them flags/volume:

```yaml
- '--docker_only=true'
- '--docker=unix:///var/run/docker.sock'
- '--store_container_labels=true'
- '--disable_metrics=disk,diskIO'
- /var/run/docker.sock:/var/run/docker.sock:ro
- /proc:/host/proc:ro
- /:/rootfs:ro,rslave
- /var/lib/docker:/var/lib/docker:ro,rslave
```

Nhung loi van con, vi cAdvisor fail trong buoc doc file `mount-id`.

### Workaround da dung trong lab

Tao lai cau truc `mount-id` ma cAdvisor dang tim. Chay tren **swarm-manager** va cac node co chay cAdvisor/container can monitor.

```bash
sudo mkdir -p /var/lib/docker/image/overlayfs/layerdb/mounts

for id in $(docker ps -q --no-trunc); do
  sudo mkdir -p /var/lib/docker/image/overlayfs/layerdb/mounts/$id
  echo "$id" | sudo tee /var/lib/docker/image/overlayfs/layerdb/mounts/$id/mount-id > /dev/null
  echo "Created mount-id for: ${id:0:12}"
done
```

Sau do restart cAdvisor service:

```bash
docker service update --force monitoring_cadvisor
```

Cho 10-30 giay roi test lai:

```bash
curl -s http://127.0.0.1:8081/metrics | grep "container_label_" | head -10
```

Neu thanh cong, metrics se co Docker/Swarm labels, vi du:

```text
container_label_com_docker_swarm_service_name="monitoring_cadvisor"
container_label_com_docker_stack_namespace="monitoring"
```

### Giai thich workaround

Docker 29 voi overlayfs dang co rootfs theo container ID, vi du:

```text
/var/lib/docker/rootfs/overlayfs/<container-id>
```

Nen tao file:

```text
/var/lib/docker/image/overlayfs/layerdb/mounts/<container-id>/mount-id
```

voi noi dung la:

```text
<container-id>
```

giup cAdvisor doc duoc read-write layer ID va khoi tao Docker handler thanh cong.

### Rui ro

- Day la workaround cho lab/portfolio, khong phai production-grade fix.
- Co tac dong vao Docker internal directory `/var/lib/docker`.
- Container moi sau khi rolling update/redeploy se co ID moi, can chay lai script.
- Docker upgrade co the doi layout tiep, workaround co the khong con dung.
- Vi co fake `mount-id`, nen tiep tuc disable disk metrics de giam rui ro doc sai disk layer:

```yaml
- '--disable_metrics=disk,diskIO'
```

### Script de chay lai khi can

Co the tao script rieng tren Swarm node:

```bash
sudo tee /usr/local/bin/fix-cadvisor-mount-id.sh > /dev/null <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/var/lib/docker/image/overlayfs/layerdb/mounts"

sudo mkdir -p "$BASE_DIR"

docker ps -q --no-trunc | while read -r id; do
  sudo mkdir -p "$BASE_DIR/$id"
  echo "$id" | sudo tee "$BASE_DIR/$id/mount-id" > /dev/null
  echo "Created mount-id for: ${id:0:12}"
done
EOF

sudo chmod +x /usr/local/bin/fix-cadvisor-mount-id.sh
```

Moi khi deploy/recreate container:

```bash
/usr/local/bin/fix-cadvisor-mount-id.sh
docker service update --force monitoring_cadvisor
```

### Bai hoc

- Neu cAdvisor chi expose `id="/"`, dung chi kiem tra Prometheus/Grafana. Hay doc log cAdvisor va kiem tra Docker handler.
- Docker Engine moi co the thay doi internal storage layout lam exporter cu khong tuong thich.
- Workaround trong `/var/lib/docker` phai ghi ro la lab-only.
- Nen co fallback monitoring: Node Exporter cho VM metrics, app `/metrics` cho application metrics, Docker Swarm discovery cho service metadata.
