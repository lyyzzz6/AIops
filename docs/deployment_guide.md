# 部署指南

## 1. 环境要求

### 1.1 硬件要求

| 组件 | 最低配置 | 推荐配置 |
|------|----------|----------|
| CPU | 4核 | 8核+ |
| 内存 | 8GB | 16GB+ |
| 磁盘 | 50GB SSD | 100GB+ SSD |
| 网络 | 100Mbps | 1Gbps |

### 1.2 软件要求

| 软件 | 版本要求 | 用途 |
|------|----------|------|
| Docker | 24.0+ | 容器运行时 |
| Docker Compose | 2.20+ | 容器编排 |
| Node.js | 20.x LTS | 前端构建 |
| JDK | 17+ | 后端运行 |
| Python | 3.10+ | 异常检测服务 |
| Git | 2.40+ | 代码管理 |

---

## 2. 快速开始

### 2.1 克隆项目

```bash
git clone https://github.com/your-org/netdata-ai-ops.git
cd netdata-ai-ops
```

### 2.2 一键启动（开发环境）

```bash
# 启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f
```

### 2.3 访问服务

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端界面 | http://localhost | Vue3前端 |
| 后端API | http://localhost:8080 | Spring Boot API |
| API文档 | http://localhost:8080/swagger-ui.html | Swagger UI |
| 异常检测 | http://localhost:8000 | FastAPI服务 |
| Milvus | http://localhost:19530 | 向量数据库 |
| Redis | http://localhost:6379 | 缓存服务 |

---

## 3. 详细部署步骤

### 3.1 基础设施部署

#### 3.1.1 Docker安装

**Linux (Ubuntu/Debian):**
```bash
# 更新包索引
sudo apt-get update

# 安装依赖
sudo apt-get install -y ca-certificates curl gnupg

# 添加Docker官方GPG密钥
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# 添加Docker仓库
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装Docker
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 启动Docker
sudo systemctl enable docker
sudo systemctl start docker

# 添加当前用户到docker组
sudo usermod -aG docker $USER
```

**Windows/macOS:**
下载并安装 Docker Desktop: https://www.docker.com/products/docker-desktop

#### 3.1.2 Milvus向量数据库

```bash
# 下载Milvus standalone配置
wget https://github.com/milvus-io/milvus/releases/download/v2.4.0/milvus-standalone-docker-compose.yml -O docker-compose-milvus.yml

# 启动Milvus
docker-compose -f docker-compose-milvus.yml up -d

# 验证Milvus运行状态
curl http://localhost:9091/api/v1/health
```

#### 3.1.3 MySQL数据库

```bash
# 启动MySQL容器
docker run -d \
  --name mysql \
  -e MYSQL_ROOT_PASSWORD=your_password \
  -e MYSQL_DATABASE=netdata_ops \
  -p 3306:3306 \
  -v mysql_data:/var/lib/mysql \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci

# 创建应用数据库
docker exec -i mysql mysql -uroot -pyour_password <<EOF
CREATE DATABASE IF NOT EXISTS netdata_ops CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'ops_user'@'%' IDENTIFIED BY 'ops_password';
GRANT ALL PRIVILEGES ON netdata_ops.* TO 'ops_user'@'%';
FLUSH PRIVILEGES;
EOF
```

#### 3.1.4 Redis缓存

```bash
# 启动Redis容器
docker run -d \
  --name redis \
  -p 6379:6379 \
  -v redis_data:/data \
  redis:7.0 \
  redis-server --appendonly yes

# 验证Redis连接
docker exec -it redis redis-cli ping
```

### 3.2 后端服务部署

#### 3.2.1 构建后端镜像

```bash
cd netdata-ai-backend

# 构建Docker镜像
docker build -t netdata-ai-backend:latest .

# 或使用Maven构建JAR包
./mvnw clean package -DskipTests
```

#### 3.2.2 配置后端服务

创建 `application-prod.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/netdata_ops?useSSL=false&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:ops_user}
    password: ${MYSQL_PASSWORD:ops_password}

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379

# Milvus配置
milvus:
  host: ${MILVUS_HOST:localhost}
  port: 19530
  database: default

# LLM配置 (生产环境使用DeepSeek)
spring.ai:
  deepseek:
    api-key: ${DEEPSEEK_API_KEY}
    base-url: https://api.deepseek.com
    chat:
      options:
        model: deepseek-chat
        temperature: 0.7

# 向量化配置
embedding:
  provider: ollama
  model: bge-m3
  dimension: 1024

# 安全配置
security:
  jwt:
    secret: ${JWT_SECRET:your-secret-key-at-least-256-bits}
    expiration: 86400000

# 审计日志
audit:
  enabled: true
  log-path: /var/log/ops/audit.log
```

#### 3.2.3 启动后端服务

```bash
# 使用Docker运行
docker run -d \
  --name netdata-ai-backend \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e MYSQL_HOST=mysql \
  -e REDIS_HOST=redis \
  -e MILVUS_HOST=milvus \
  -e DEEPSEEK_API_KEY=your_key \
  -v /var/log/ops:/var/log/ops \
  netdata-ai-backend:latest

# 或直接运行JAR包
java -jar target/netdata-ai-backend-1.0.0.jar --spring.profiles.active=prod
```

### 3.3 异常检测服务部署

#### 3.3.1 构建Python服务

```bash
cd anomaly-detector

# 创建虚拟环境
python -m venv venv
source venv/bin/activate  # Linux/macOS
# venv\Scripts\activate   # Windows

# 安装依赖
pip install -r requirements.txt

# 构建Docker镜像
docker build -t anomaly-detector:latest .
```

#### 3.3.2 配置异常检测服务

创建 `.env` 文件:

```env
# 服务配置
HOST=0.0.0.0
PORT=8000
WORKERS=4

# NetData配置
NETDATA_HOST=localhost
NETDATA_PORT=19999

# 模型配置
DEFAULT_DETECTOR=iforest
MODEL_DIR=/app/models

# 日志配置
LOG_LEVEL=INFO
LOG_FILE=/var/log/anomaly-detector/app.log
```

#### 3.3.3 启动异常检测服务

```bash
# 使用Docker运行
docker run -d \
  --name anomaly-detector \
  -p 8000:8000 \
  -e NETDATA_HOST=host.docker.internal \
  -v anomaly_models:/app/models \
  anomaly-detector:latest

# 或直接运行
uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 4
```

### 3.4 前端部署

#### 3.4.1 构建前端

```bash
cd netdata-ai-frontend

# 安装依赖
npm install

# 构建生产版本
npm run build
```

#### 3.4.2 Nginx配置

创建 `nginx.conf`:

```nginx
upstream backend {
    server backend:8080;
}

upstream anomaly_detector {
    server anomaly-detector:8000;
}

server {
    listen 80;
    server_name _;

    # 前端静态文件
    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    # API代理
    location /api/ {
        proxy_pass http://backend/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket代理
    location /api/ws/ {
        proxy_pass http://backend/api/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }

    # 异常检测API代理
    location /detect/ {
        proxy_pass http://anomaly_detector/detect/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
        root /usr/share/nginx/html;
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # Gzip压缩
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;
    gzip_min_length 1000;
}
```

#### 3.4.3 构建前端镜像

```dockerfile
# Dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

```bash
# 构建镜像
docker build -t netdata-ai-frontend:latest .

# 运行容器
docker run -d \
  --name netdata-ai-frontend \
  -p 80:80 \
  netdata-ai-frontend:latest
```

---

## 4. Docker Compose完整配置

### 4.1 docker-compose.yml

```yaml
version: '3.8'

services:
  # 前端服务
  frontend:
    build:
      context: ./netdata-ai-frontend
      dockerfile: Dockerfile
    ports:
      - "80:80"
    depends_on:
      - backend
    networks:
      - ops-network
    restart: unless-stopped

  # 后端服务
  backend:
    build:
      context: ./netdata-ai-backend
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - MYSQL_HOST=mysql
      - MYSQL_USER=ops_user
      - MYSQL_PASSWORD=ops_password
      - REDIS_HOST=redis
      - MILVUS_HOST=milvus
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
      - JWT_SECRET=${JWT_SECRET:-your-secret-key-at-least-256-bits}
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_started
      milvus:
        condition: service_started
    volumes:
      - backend-logs:/var/log/ops
    networks:
      - ops-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  # 异常检测服务
  anomaly-detector:
    build:
      context: ./anomaly-detector
      dockerfile: Dockerfile
    ports:
      - "8000:8000"
    environment:
      - NETDATA_HOST=host.docker.internal
      - NETDATA_PORT=19999
    volumes:
      - anomaly-models:/app/models
    networks:
      - ops-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  # Milvus向量数据库
  milvus:
    image: milvusdb/milvus:v2.4.0
    command: ["milvus", "run", "standalone"]
    ports:
      - "19530:19530"
      - "9091:9091"
    volumes:
      - milvus-data:/var/lib/milvus
    environment:
      - ETCD_USE_EMBED=true
      - COMMON_STORAGETYPE=local
    networks:
      - ops-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/api/v1/health"]
      interval: 30s
      timeout: 10s
      retries: 5

  # MySQL数据库
  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root_password
      - MYSQL_DATABASE=netdata_ops
      - MYSQL_USER=ops_user
      - MYSQL_PASSWORD=ops_password
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init-scripts:/docker-entrypoint-initdb.d
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
    networks:
      - ops-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Redis缓存
  redis:
    image: redis:7.0-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    networks:
      - ops-network
    restart: unless-stopped

networks:
  ops-network:
    driver: bridge

volumes:
  milvus-data:
  mysql-data:
  redis-data:
  backend-logs:
  anomaly-models:
```

### 4.2 启动命令

```bash
# 启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f backend
docker-compose logs -f anomaly-detector

# 停止服务
docker-compose down

# 停止并删除数据卷
docker-compose down -v

# 重新构建并启动
docker-compose up -d --build
```

---

## 5. 生产环境部署

### 5.1 Kubernetes部署

#### 5.1.1 Namespace配置

```yaml
# namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: netdata-ops
```

#### 5.1.2 ConfigMap配置

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: netdata-ops
data:
  SPRING_PROFILES_ACTIVE: "prod"
  MYSQL_HOST: "mysql-service"
  REDIS_HOST: "redis-service"
  MILVUS_HOST: "milvus-service"
```

#### 5.1.3 Secret配置

```yaml
# secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
  namespace: netdata-ops
type: Opaque
stringData:
  MYSQL_PASSWORD: "your_password"
  DEEPSEEK_API_KEY: "your_api_key"
  JWT_SECRET: "your_jwt_secret"
```

#### 5.1.4 Deployment配置

```yaml
# backend-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: backend
  namespace: netdata-ops
spec:
  replicas: 3
  selector:
    matchLabels:
      app: backend
  template:
    metadata:
      labels:
        app: backend
    spec:
      containers:
      - name: backend
        image: netdata-ai-backend:latest
        ports:
        - containerPort: 8080
        envFrom:
        - configMapRef:
            name: app-config
        - secretRef:
            name: app-secrets
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

#### 5.1.5 Service配置

```yaml
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: backend-service
  namespace: netdata-ops
spec:
  selector:
    app: backend
  ports:
  - port: 8080
    targetPort: 8080
  type: ClusterIP

---
apiVersion: v1
kind: Service
metadata:
  name: frontend-service
  namespace: netdata-ops
spec:
  selector:
    app: frontend
  ports:
  - port: 80
    targetPort: 80
  type: LoadBalancer
```

### 5.2 Ingress配置

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ops-ingress
  namespace: netdata-ops
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - ops.yourdomain.com
    secretName: ops-tls
  rules:
  - host: ops.yourdomain.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: frontend-service
            port:
              number: 80
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: backend-service
            port:
              number: 8080
```

---

## 6. 监控与日志

### 6.1 Prometheus监控

```yaml
# prometheus-config.yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'backend'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['backend-service:8080']

  - job_name: 'anomaly-detector'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['anomaly-detector-service:8000']
```

### 6.2 Grafana仪表板

导入以下仪表板:
- Spring Boot Dashboard: ID 12900
- JVM Dashboard: ID 4701
- Custom Ops Dashboard: 自定义仪表板

### 6.3 ELK日志收集

```yaml
# logstash.conf
input {
  file {
    path => "/var/log/ops/*.log"
    start_position => "beginning"
  }
}

filter {
  grok {
    match => { "message" => "\[%{TIMESTAMP_ISO8601:timestamp}\] \[%{LOGLEVEL:level}\] \[%{DATA:traceId}\] \[%{DATA:class}\] - %{GREEDYDATA:content}" }
  }
}

output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    index => "ops-logs-%{+YYYY.MM.dd}"
  }
}
```

---

## 7. 备份与恢复

### 7.1 MySQL备份

```bash
# 备份
docker exec mysql mysqldump -uroot -p${MYSQL_ROOT_PASSWORD} netdata_ops > backup_$(date +%Y%m%d).sql

# 恢复
cat backup_20240430.sql | docker exec -i mysql mysql -uroot -p${MYSQL_ROOT_PASSWORD} netdata_ops
```

### 7.2 Milvus备份

```bash
# 使用Milvus Backup工具
milvus-backup create -n backup_$(date +%Y%m%d)

# 恢复
milvus-backup restore -n backup_20240430
```

### 7.3 Redis备份

```bash
# 备份
docker exec redis redis-cli BGSAVE
docker cp redis:/data/dump.rdb ./redis_backup_$(date +%Y%m%d).rdb

# 恢复
docker cp ./redis_backup_20240430.rdb redis:/data/dump.rdb
docker restart redis
```

---

## 8. 故障排查

### 8.1 常见问题

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| 服务启动失败 | 端口冲突 | 检查端口占用，修改端口配置 |
| 数据库连接失败 | 密码错误/网络问题 | 检查凭据和网络连接 |
| 向量检索慢 | 索引未优化 | 检查Milvus索引配置 |
| 内存溢出 | JVM配置不当 | 调整JVM堆大小 |
| 响应超时 | LLM API慢 | 检查LLM服务状态 |

### 8.2 日志查看

```bash
# 查看后端日志
docker-compose logs -f backend --tail=100

# 查看异常检测服务日志
docker-compose logs -f anomaly-detector --tail=100

# 查看所有服务日志
docker-compose logs -f --tail=50
```

### 8.3 健康检查

```bash
# 后端健康检查
curl http://localhost:8080/actuator/health

# 异常检测服务健康检查
curl http://localhost:8000/health

# Milvus健康检查
curl http://localhost:9091/api/v1/health

# Redis连接检查
docker exec redis redis-cli ping
```

---

## 9. 安全加固

### 9.1 网络安全

```bash
# 配置防火墙
sudo ufw allow 80/tcp    # HTTP
sudo ufw allow 443/tcp   # HTTPS
sudo ufw allow 22/tcp    # SSH
sudo ufw enable

# 限制内部端口访问
sudo ufw deny 3306/tcp   # MySQL
sudo ufw deny 6379/tcp   # Redis
sudo ufw deny 19530/tcp  # Milvus
```

### 9.2 TLS配置

```yaml
# 应用TLS配置
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_PASSWORD}
    key-store-type: PKCS12
```

### 9.3 密钥管理

```bash
# 使用环境变量
export DEEPSEEK_API_KEY="your_api_key"
export JWT_SECRET="your_jwt_secret"

# 或使用Docker Secrets
echo "your_api_key" | docker secret create deepseek_api_key -
```
