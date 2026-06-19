# 🚀 MERN CI/CD Pipeline — Jenkins · Harbor · Docker Swarm · Monitoring

Hệ thống CI/CD hoàn chỉnh (Production-like) cho ứng dụng Full-stack MERN trên Google Cloud Platform. Tự động hóa toàn bộ quy trình từ lúc code được push cho đến khi deploy zero-downtime lên server, tích hợp bảo mật và giám sát hệ thống thời gian thực.

**Kết quả đạt được:** So với quy trình deploy thủ công (SSH, build, kiểm tra), pipeline tự động giúp rút ngắn thời gian triển khai từ hàng chục phút thao tác tay xuống còn **khoảng 6.5 phút** (thời gian chạy Full Pipeline thực tế trên Jenkins, bao gồm cả Security Scan và Healthcheck), loại bỏ hoàn toàn rủi ro do sai sót con người, và đưa thời gian rollback xuống dưới 30 giây nhờ versioned image trên Harbor.

---

## 📌 Tổng quan Pipeline & Công nghệ (Tech Stack)

![CI/CD Pipeline Architecture](picture/pipline.png)

| Lớp (Layer) | Công nghệ sử dụng | Điểm nổi bật trong dự án |
|---|---|---|
| **Application** | MERN Stack (MongoDB, Express, React, Node.js) | Tách biệt hoàn toàn Frontend/Backend, dễ dàng scale. |
| **CI/CD Engine** | **Jenkins Pipeline (Groovy) + Webhooks** | **Pipeline as Code, Parameterized Builds, Auto webhook.** |
| **Container Registry**| Harbor (Self-hosted, Private) | Quản lý phiên bản Image (Versioned Tags), bảo mật Registry. |
| **Orchestration** | Docker Swarm (Multi-node Cluster) | Rolling Update Zero-downtime, tự phục hồi (Self-healing). |
| **Security Scan** | Trivy (Vulnerability Scanner) | DevSecOps: Chặn đứng lỗ hổng HIGH/CRITICAL trước khi deploy. |
| **Monitoring** | Prometheus, Grafana, Node Exporter, cAdvisor | Dashboards thời gian thực, **Hệ thống Cảnh báo (Alerts)**. |
| **Infrastructure** | Google Cloud Platform (3 VMs) | Phân tách Manager, Worker và Registry an toàn. |

![GCP VM Instances](picture/GCP-VM.png)

### 📁 Cấu trúc Thư mục (Repository Layout)

```text
.
├── Jenkinsfile.production.groovy       # Kịch bản Pipeline tự động cho Production (7 stages)
├── Jenkinsfile.staging.groovy          # Kịch bản Pipeline linh hoạt cho Staging (Parameterized)
├── demo-troubleshooting-notes.md       # Sổ tay ghi chép lỗi thực tế và cách fix (cAdvisor)
├── picture/                            # Chứa ảnh minh họa hệ thống và dashboards
└── source/
    ├── backend/                        # Source code Backend (Node.js) & Dockerfile multi-stage
    ├── frontend/                       # Source code Frontend (React) & Dockerfile unprivileged
    ├── docker-compose.staging.yml      # Cấu hình Swarm Stack cho Staging
    ├── docker-compose.production.yml   # Cấu hình Swarm Stack Zero-downtime cho Production
    ├── docker-compose.monitoring.yml   # Cấu hình Stack Monitoring (Prometheus, Grafana, cAdvisor)
    └── monitoring/                     # Thư mục chứa cấu hình tĩnh (prometheus.yml, grafana dashboards)
```

---

## 🛠️ CHI TIẾT 4 GIAI ĐOẠN (STAGES) TRIỂN KHAI

Dự án được chia thành 4 giai đoạn chính, mô phỏng chuẩn xác luồng làm việc DevOps thực tế trong doanh nghiệp, với **Jenkins là "trái tim" điều phối mọi hoạt động.**

---

### STAGE 1: Container hóa ứng dụng MERN & Setup Repository

![Stage 1 - Containerize](picture/stage1.png)

Trong giai đoạn này, ứng dụng MERN được đóng gói vào các Docker container. **Điểm ăn tiền của phần này là khả năng viết Dockerfile tối ưu:** image phải siêu nhẹ, build cực nhanh nhờ cache, và đặc biệt phải bảo mật tuyệt đối (chạy dưới quyền non-root).

#### 1. Dockerize Backend (Node.js) - Tối ưu bảo mật
Sử dụng Multi-stage build để cài đặt dependencies riêng. Ở runtime, `npm` và các tool thừa bị loại bỏ hoàn toàn.

```dockerfile
# Stage 1: Cài dependencies tách riêng (tận dụng Docker cache layer)
FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev

# Stage 2: Runtime - chỉ giữ những gì cần thiết để chạy app
FROM node:22-alpine AS runtime
# Cập nhật OS packages (vá lỗ hổng bảo mật) + Xóa npm/npx khỏi runtime (giảm attack surface)
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx \
    && addgroup -g 1001 -S appgroup \
    && adduser -u 1001 -S appuser -G appgroup
WORKDIR /app
COPY --from=deps --chown=appuser:appgroup /app/node_modules ./node_modules
COPY --chown=appuser:appgroup package*.json ./
COPY --chown=appuser:appgroup server.js entrypoint.sh ./
RUN chmod +x /app/entrypoint.sh

# Chạy container dưới quyền non-root (UID 1001)
USER appuser
EXPOSE 5000

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:5000/health || exit 1

# entrypoint.sh đọc Docker Secret từ /run/secrets/backend_env và export thành env vars
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["node", "server.js"]
```

#### 2. Dockerize Frontend (React + Nginx) - Unprivileged
React được build ra các file tĩnh HTML/JS/CSS, sau đó dùng Nginx để serve. Tránh dùng Nginx bản mặc định (yêu cầu quyền root để binding port 80).

```dockerfile
# Stage 1: Build React app
FROM node:18-alpine AS build-stage
# BUILD_ENV quyết định file .env nào được load (staging hay production)
ARG BUILD_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build:${BUILD_ENV}

# Stage 2: Serve bằng Nginx Unprivileged (non-root, port 8080)
FROM nginxinc/nginx-unprivileged:alpine AS production-stage
COPY --from=build-stage /app/build /usr/share/nginx/html
# Custom nginx config cho React SPA (xử lý client-side routing)
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
```

---

### STAGE 2: Setup cho CI/CD Pipelines & Docker Swarm

![Stage 2 - Setup Pipelines](picture/stage2.png)

Giai đoạn xây dựng nền móng hạ tầng: kết nối Jenkins với Harbor bằng credentials, tạo network overlay cho Docker Swarm, và cấu hình các Jenkins Pipeline kịch bản.

#### Điểm sáng Kỹ thuật: Cấu hình Zero-downtime Deployment
Cấu hình `docker-compose.production.yml` được tối ưu hóa cho môi trường Swarm để đảm bảo người dùng không bị gián đoạn khi hệ thống deploy:

```yaml
services:
  frontend:
    image: ${HARBOR_HOST}/mern/frontend:${IMAGE_TAG:-latest}
    ports:
      - "80:8080"
    deploy:
      replicas: 2
      update_config:
        parallelism: 1         # Cập nhật từng container một
        delay: 10s             # Chờ 10s giữa mỗi lần cập nhật
        order: start-first     # QUAN TRỌNG: Khởi động container mới lên trước, khi nào sống (pass healthcheck) mới tắt container cũ
        failure_action: rollback # Tự động lùi về phiên bản cũ ngay lập tức nếu deploy lỗi
      placement:
        constraints:
          - node.labels.env == worker
```

---

### STAGE 3: Deploy to Staging Server (Linh hoạt với Jenkins Parameters)

![Stage 3 - Deploy Staging](picture/stage3.png)

Môi trường Staging (Dành cho QA/Tester) không cần tự động hóa hoàn toàn mà cần sự linh hoạt cao độ. Tôi khai thác tối đa sức mạnh của **Jenkins Active Choices Plugin** để tạo giao diện điều khiển (Parameterized Pipeline).

![Jenkins Staging Pipeline Actions](picture/jenkin-staging-action.png)

Pipeline cung cấp giao diện trực quan cho Developer/QA với các nút:
- ▶️ **START**: Bật hệ thống Staging.
- ⏹️ **STOP**: Tắt hạ tầng Staging đi khi không dùng.
- ⬆️ **UP_CODE (Selective Deploy)**: Chỉ build Frontend hoặc Backend để tiết kiệm tối đa thời gian chờ đợi của Dev.
- ⏪ **ROLLBACK**: Quay lại version cũ (Groovy Script trong Jenkins tự động gọi Harbor API để lấy danh sách version tags thả vào Dropdown).

![Jenkins Staging Overview](picture/jenkin-staging.png)

#### Trích đoạn Jenkinsfile: Xử lý Logic Parametized Bằng Groovy Script
Jenkinsfile sử dụng cú pháp Groovy mạnh mẽ với khối `try/catch` để điều hướng logic triển khai:
```groovy
// Logic lựa chọn chức năng thông minh dựa trên biến môi trường (Params)
stage('Deploy to Staging') {
    if (params.BUILD_SERVICES == 'all') {
        deployStack(COMPOSE_FILE, STACK_NAME, buildTag)
    } else if (params.BUILD_SERVICES == 'backend') {
        sh "docker service update --with-registry-auth --image ${backendImage}:${buildTag} ${STACK_NAME}_backend"
    }
}
```

---

### STAGE 4: Deploy to Production Server & Hệ thống Alerting

![Stage 4 - Deploy Production](picture/stage4.png)

Production (Môi trường người dùng thật) yêu cầu ổn định tuyệt đối. Pipeline Production là một kiệt tác tự động hoá 100%, được **Trigger bằng Webhook** từ GitHub, xử lý qua 7 bước nghiêm ngặt.

![Jenkins Production Overview](picture/jenkin-production.png)

#### 1. Luồng chạy Jenkins 7 Bước (Tổng thời gian ~6.5 phút)
1. **Checkout**: Lấy code từ `main` tự động qua Webhook.
2. **Test**: Jenkins gọi Node Tool chạy Unit tests (bảo vệ chức năng).
3. **Build Images**: Tạo Docker images gán tag tự động bằng `${env.BUILD_NUMBER}`.
4. **Security Scan (DevSecOps)**: Jenkins tích hợp Trivy. **Nếu Trivy phát hiện lỗi HIGH/CRITICAL, Jenkins báo FAIL và chặn deploy lập tức.**
5. **Push to Harbor**: Jenkins gọi lệnh docker login an toàn qua hàm `withCredentials(...)` để giấu thông tin mật khẩu, đẩy lên Harbor.
6. **Deploy to Swarm**: Roll-out lên Swarm theo chính sách Zero-downtime.
7. **Health Check**: Jenkins chạy vòng lặp kiểm tra sức khỏe endpoint, đảm bảo app trả về HTTP 200 mới đánh dấu Pipeline màu xanh (Success).

#### 2. Giám sát hệ thống & Alerting (Monitoring)
Hệ thống được thiết kế với "đôi mắt" túc trực ngày đêm: Prometheus + Grafana thu thập metrics của Host (Node Exporter) và Container (cAdvisor).

![Grafana Dashboard 1](picture/grafana.png)
![Grafana Dashboard 2](picture/grafana-2.png)

*Dashboard thể hiện rõ lượng tài nguyên tiêu thụ từng service, số lượng container đang chạy, giúp phát hiện Memory Leak.*

**Hệ thống Cảnh báo sớm (Alerting Rules):**
*(Ghi chú: Hình ảnh chi tiết Alerting sẽ cập nhật sau)*
Dự án không chỉ vẽ biểu đồ mà còn cài đặt các **Alert Rules**, sẵn sàng đẩy thông báo nếu hệ thống có vấn đề:
- 🔴 **cAdvisor Down**: Cảnh báo khi mất liên lạc với hệ thống thu thập metrics.
- 🟡 **Docker Labels Missing**: Phát hiện sự cố cAdvisor trên phiên bản Docker mới.
- 🟠 **High CPU/Memory Usage**: Cảnh báo khi một service ăn hơn 80% RAM hoặc CPU.

---

## 🔧 Kỹ năng Xử lý sự cố (Troubleshooting & Debugging)

Làm DevOps không chỉ là chạy tool, mà là kỹ năng Debug hệ thống khi xảy ra sự cố. Dưới đây là bài học đắt giá của tôi trong dự án:

**Sự cố:** Mất toàn bộ metadata của Docker Swarm Container trên Grafana.
- **Nguyên nhân:** Khi sử dụng Docker Engine 29+, cấu trúc lưu trữ nội bộ `layerdb` bị Docker thay đổi, khiến cAdvisor bị crash loop và mất nhãn (labels).
- **Xử lý:** Tôi không bỏ cuộc hay hạ cấp hệ điều hành. Tôi tự research, viết một kịch bản **Shell Script kết hợp Cronjob Linux** chạy mỗi phút để tạo các file dummy `mount-id` giả lập môi trường, đánh lừa cAdvisor hoạt động mượt mà trở lại.

Chi tiết phân tích lỗi, đọc tại file: [👉 demo-troubleshooting-notes.md](demo-troubleshooting-notes.md).

---

## 🏆 ĐIỂM CHẠM VỚI NHÀ TUYỂN DỤNG (Why I Fit For Your Team)

1. **Jenkins Expertise:** Làm chủ Jenkins Pipeline-as-Code bằng Groovy (không dùng UI click), tạo Parameterized Build với Active Choices Plugin, và tích hợp Webhook tự động.
2. **Tư duy Production-ready:** Thiết kế kiến trúc triển khai rolling update (cập nhật gối đầu) đảm bảo tính sẵn sàng cao, cấu hình tự động phục hồi khi lỗi, và thiết lập giới hạn tài nguyên (resource limits) cho container.
3. **DevSecOps:** Tích hợp quy trình quét lỗ hổng bảo mật Trivy vào pipeline, chặn đứng rủi ro trước khi deploy. Áp dụng chuẩn bảo mật bằng non-root containers, Docker Secrets và network isolation.
4. **Khả năng Debug thực chiến:** Khắc phục thành công sự cố tương thích phiên bản (ví dụ: cAdvisor và Docker Engine 29+) bằng tư duy phân tích nguyên nhân gốc rễ và xử lý bằng Shell Script. Các bài học được ghi chép cẩn thận trong [Sổ tay Troubleshooting](demo-troubleshooting-notes.md).
5. **Tối ưu chi phí vận hành:** Pipeline Staging được thiết kế để hỗ trợ deploy chọn lọc (chỉ build service cần thiết) và có cơ chế dọn dẹp tài nguyên (cleanup) tự động giúp tiết kiệm chi phí cloud.
