
# 智能运维问答与执行系统

## 📋 项目概述

面向 NetData 监控数据的智能运维问答与执行系统，集成了大语言模型、向量数据库、知识图谱和异常检测等技术，提供智能问答、知识库管理、命令执行审批、实时告警和异常检测等功能。

**作者：** 刘一舟  
**版本：** 1.0.0

### 主要功能

- 🤖 **智能问答**：基于 RAG 的运维知识问答，支持多轮对话
- 📚 **知识库管理**：文档上传、向量索引、语义检索
- 🔧 **命令执行**：智能命令生成与审批流程
- 🚨 **实时告警**：异常检测与告警推送
- 👥 **用户权限**：完整的用户、角色、权限管理
- 📊 **运维分析**：监控数据可视化与趋势分析

---

## 🛠️ 技术栈

### 前端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.4 | 前端框架 |
| TypeScript | 5.4 | 类型安全 |
| Vite | 5.2 | 构建工具 |
| Element Plus | 2.6 | UI 组件库 |
| Pinia | 2.1 | 状态管理 |
| Vue Router | 4.3 | 路由管理 |
| Axios | 1.6 | HTTP 客户端 |
| ECharts | 6.1 | 图表可视化 |

### 后端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.3.6 | Java 后端框架 |
| Spring AI | 1.0.0-M5 | LLM 集成框架 |
| Spring Security | 3.3 | 安全认证 |
| MyBatis Plus | 3.5.5 | ORM 框架 |
| JWT | 0.12.5 | Token 认证 |
| Resilience4j | 2.2.0 | 容错框架 |

### 数据库与中间件

| 技术 | 版本 | 说明 |
|------|------|------|
| MySQL | 8.0 | 关系型数据库 |
| Redis | 7.2 | 缓存、会话存储 |
| Milvus | 2.4.15 | 向量数据库 |
| Ollama | latest | 本地 LLM 推理 |
| MinIO | 2023-03-20 | 对象存储 |
| etcd | 3.5.16 | 服务发现（Milvus 依赖） |

### 异常检测服务

| 技术 | 说明 |
|------|------|
| Python 3.x | 编程语言 |
| FastAPI | Web 框架 |
| PyOD | 异常检测算法库 |
| PySAD | 流式异常检测 |
| scikit-learn | 机器学习库 |

---

## 📦 环境要求

### 系统要求

- **操作系统**：Windows 10/11, macOS 12+, Linux (Ubuntu 20.04+)
- **内存**：建议 16GB+（Milvus 至少需要 4GB）
- **磁盘**：至少 30GB 可用空间
- **CPU**：4 核心及以上

### Docker 要求

- **Docker Engine**：24.0.0+
- **Docker Compose**：2.20.0+
- **资源分配**：
  - CPU：至少 4 核心
  - 内存：至少 16GB（推荐 20GB+）
  - 磁盘：至少 100GB

### 网络要求

- 能够访问外网（拉取 Docker 镜像、API 调用）
- 如使用 DeepSeek API，需确保网络连通性

---

## 🚀 快速开始

### 1. 克隆或下载项目

```bash
cd 毕设
```

### 2. 配置环境变量

```bash
# 复制示例配置
cp .env.example .env
```

编辑 `.env` 文件，修改以下重要配置：

```env
# MySQL 密码（必须修改！）
MYSQL_ROOT_PASSWORD=your_strong_password
MYSQL_PASSWORD=your_strong_password

# Redis 密码（必须修改！）
REDIS_PASSWORD=your_strong_password

# MinIO 凭证（必须修改！）
MINIO_ACCESS_KEY=your_access_key
MINIO_SECRET_KEY=your_secret_key

# DeepSeek API Key（从 https://platform.deepseek.com/ 获取）
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
```

### 3. 一键启动所有服务

```bash
docker-compose up -d
```

首次启动会：
- 拉取所有 Docker 镜像（约 5-10 分钟）
- 初始化数据库
- 启动所有服务

### 4. 验证服务启动

```bash
# 查看服务状态
docker-compose ps
```

所有服务状态应为 `healthy`。

### 5. 访问应用

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端 | http://localhost:3000 | 主应用界面 |
| 后端 API | http://localhost:8080 | API 服务 |
| Swagger 文档 | http://localhost:8080/swagger-ui.html | API 文档 |
| Milvus gRPC | localhost:19530 | 向量数据库 |
| MinIO Console | http://localhost:9001 | 对象存储管理 |
| 异常检测服务 | http://localhost:8001/api/docs | 异常检测 API |

### 6. 默认登录账号

- **用户名**：`admin`
- **密码**：`admin123`

---

## 📖 详细部署步骤

### 前置准备

1. 确保已安装 Docker 和 Docker Compose
2. 检查 Docker 资源分配是否满足要求

```bash
# 查看 Docker 信息
docker info

# 查看 Docker Compose 版本
docker-compose --version
```

### 完整部署流程

#### 步骤 1：项目目录结构检查

确保项目结构如下：

```
毕设/
├── .env.example
├── docker-compose.yml
├── config/
│   ├── milvus_collection.yaml
│   ├── mysql/
│   └── redis/
├── sql/
│   └── init.sql
├── netdata-ai-backend/
│   ├── Dockerfile
│   └── pom.xml
├── netdata-ai-frontend/
│   ├── Dockerfile
│   └── package.json
└── anomaly-detection-service/
    ├── Dockerfile
    └── requirements.txt
```

#### 步骤 2：创建必要的目录

```bash
# Windows PowerShell
mkdir -p data/mysql,data/milvus,data/redis,data/ollama,data/anomaly_models,logs/anomaly

# Linux/macOS
mkdir -p data/mysql data/milvus data/redis data/ollama data/anomaly_models logs/anomaly
```

#### 步骤 3：构建并启动服务

```bash
# 构建并启动所有服务
docker-compose up -d --build

# 查看日志
docker-compose logs -f

# 仅查看特定服务日志
docker-compose logs -f backend
docker-compose logs -f frontend
```

#### 步骤 4：初始化 Ollama 模型（可选）

如需要使用本地 Ollama 模型：

```bash
# 进入 Ollama 容器
docker exec -it netdata-ops-ollama /bin/bash

# 拉取模型
ollama pull qwen2.5:7b
ollama pull bge-m3

# 验证模型
ollama list
```

#### 步骤 5：验证系统功能

1. 打开浏览器访问 http://localhost:3000
2. 使用默认账号登录
3. 测试问答功能
4. 上传测试文档到知识库
5. 查看告警仪表板

---

## 🐳 Docker 使用说明

### 常用命令

```bash
# 启动所有服务
docker-compose up -d

# 停止所有服务
docker-compose down

# 停止并删除数据卷（慎用！会清除所有数据）
docker-compose down -v

# 重启服务
docker-compose restart

# 查看服务状态
docker-compose ps

# 查看实时日志
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f backend

# 进入容器
docker exec -it netdata-ops-mysql bash
docker exec -it netdata-ops-backend bash

# 查看资源使用
docker stats
```

### 单个服务管理

```bash
# 仅启动某个服务及其依赖
docker-compose up -d mysql

# 重启某个服务
docker-compose restart backend

# 重建某个服务
docker-compose up -d --build frontend
```

### 服务端口映射

| 服务 | 容器端口 | 主机端口 | 说明 |
|------|----------|----------|------|
| Frontend | 80 | 3000 | 前端服务 |
| Backend | 8080 | 8080 | 后端 API |
| MySQL | 3306 | 3307 | 数据库 |
| Redis | 6379 | 6379 | 缓存 |
| Milvus | 19530 | 19530 | 向量数据库 |
| Milvus | 9091 | 9091 | Metrics |
| MinIO | 9000 | 9000 | 对象存储 |
| MinIO | 9001 | 9001 | Console |
| Ollama | 11434 | 11434 | LLM 服务 |
| Anomaly Detection | 8001 | 8001 | 异常检测 |

---

## 💾 数据库初始化说明

### MySQL 数据库

数据库初始化由 Docker 自动完成：

1. 创建数据库 `netdata_ops`
2. 创建用户 `ops_user`
3. 执行 `sql/init.sql` 初始化表结构

### 手动初始化（如需要）

```bash
# 进入 MySQL 容器
docker exec -it netdata-ops-mysql bash

# 登录 MySQL
mysql -u root -p
# 输入 MYSQL_ROOT_PASSWORD

# 使用数据库
USE netdata_ops;

# 查看表
SHOW TABLES;
```

### Milvus 向量数据库

Milvus 会在首次启动时自动创建必要的集合，也可以通过后端 API 初始化。

---

## ❓ 常见问题解答

### 1. 端口被占用怎么办？

编辑 `.env` 文件，修改冲突的端口：

```env
MYSQL_PORT=3308
REDIS_PORT=6380
BACKEND_PORT=8081
FRONTEND_PORT=3001
```

### 2. Milvus 启动失败？

- 检查 Docker 内存分配是否满足要求（至少 4GB）
- 查看日志：`docker-compose logs milvus-standalone`
- 确认 etcd 和 minio 已健康启动

### 3. 后端连接 Milvus 失败？

确保：
- Milvus 容器状态为 `healthy`
- 后端配置使用容器内部端口 `19530`（不是主机端口）
- 检查网络配置：`docker network inspect netdata-ops-network`

### 4. 如何重置数据库？

```bash
# 停止服务并删除数据卷
docker-compose down -v

# 重新启动
docker-compose up -d
```

⚠️ **警告**：这将清除所有数据！

### 5. DeepSeek API 调用失败？

- 检查 `DEEPSEEK_API_KEY` 是否正确配置
- 确认网络可以访问 `https://api.deepseek.com`
- 检查 API Key 是否还有额度

### 6. 前端无法连接后端？

- 确认后端服务状态：`docker-compose ps backend`
- 检查后端健康检查：`curl http://localhost:8080/api/v1/health`
- 查看前端容器日志：`docker-compose logs frontend`

### 7. Ollama 模型下载慢？

可以预先下载模型文件并挂载，或配置代理。

### 8. 如何备份数据？

```bash
# 备份 MySQL
docker exec netdata-ops-mysql mysqldump -u root -p netdata_ops &gt; backup.sql

# 备份数据目录
tar -czf backup-data.tar.gz data/
```

---

## 📁 项目目录结构说明

```
毕设/
├── .env.example                    # 环境变量示例配置
├── .env                            # 实际环境变量配置（需创建）
├── .gitignore                      # Git 忽略文件
├── docker-compose.yml              # Docker Compose 配置
├── README.md                       # 项目说明文档
│
├── config/                         # 配置文件目录
│   ├── milvus_collection.yaml      # Milvus 集合配置
│   ├── mysql/                      # MySQL 配置
│   │   └── my.cnf
│   └── redis/                      # Redis 配置
│       └── redis.conf
│
├── sql/                            # 数据库脚本
│   └── init.sql                    # 数据库初始化脚本
│
├── data/                           # 数据持久化目录（自动创建）
│   ├── mysql/                      # MySQL 数据
│   ├── milvus/                     # Milvus 数据
│   │   ├── etcd/                   # etcd 数据
│   │   └── minio/                  # MinIO 数据
│   ├── redis/                      # Redis 数据
│   ├── ollama/                     # Ollama 模型数据
│   └── anomaly_models/             # 异常检测模型
│
├── logs/                           # 日志目录
│   └── anomaly/                    # 异常检测服务日志
│
├── netdata-ai-frontend/            # 前端项目
│   ├── src/                        # 源代码
│   │   ├── api/                    # API 接口
│   │   ├── assets/                 # 静态资源
│   │   ├── components/             # 公共组件
│   │   ├── directives/             # Vue 指令
│   │   ├── router/                 # 路由配置
│   │   ├── stores/                 # Pinia 状态管理
│   │   ├── types/                  # TypeScript 类型定义
│   │   ├── views/                  # 页面视图
│   │   ├── App.vue                 # 根组件
│   │   └── main.ts                 # 入口文件
│   ├── Dockerfile                  # 前端 Dockerfile
│   ├── nginx.conf                  # Nginx 配置
│   ├── package.json                # 依赖配置
│   ├── vite.config.ts              # Vite 配置
│   └── tsconfig.json               # TypeScript 配置
│
├── netdata-ai-backend/             # 后端项目
│   ├── src/                        # 源代码
│   │   └── main/
│   │       ├── java/                # Java 源码
│   │       └── resources/           # 配置文件
│   │           └── application.yml # Spring Boot 配置
│   ├── Dockerfile                  # 后端 Dockerfile
│   └── pom.xml                     # Maven 配置
│
├── anomaly-detection-service/      # 异常检测服务
│   ├── app/                        # 应用源码
│   │   ├── api/                    # API 路由
│   │   ├── core/                   # 核心检测逻辑
│   │   ├── netdata/                # NetData 客户端
│   │   ├── services/               # 业务服务
│   │   ├── config.py               # 配置文件
│   │   └── main.py                 # 入口文件
│   ├── Dockerfile                  # Dockerfile
│   ├── requirements.txt            # Python 依赖
│   └── pyproject.toml              # 项目配置
│
├── evaluation/                     # 评估脚本
│   ├── run_evaluation.py
│   └── test_cases.json
│
├── scripts/                        # 辅助脚本
│   ├── Dockerfile.mock-netdata     # Mock Netdata 服务
│   └── ...
│
├── import_rag_test_data.py         # RAG 测试数据导入
├── rag_test_documents.json         # RAG 测试文档
├── rag_validation_test.py          # RAG 验证测试
├── rag_validation_test_data.json   # RAG 验证数据
├── 前端响应.txt                    # 前端测试响应
└── 后端日志 .txt                   # 后端测试日志
```

---

## 🤝 开发指南

### 本地开发前端

```bash
cd netdata-ai-frontend
npm install
npm run dev
```

### 本地开发后端

```bash
cd netdata-ai-backend
# 配置好本地 MySQL、Redis、Milvus
mvn spring-boot:run
```

### 本地开发异常检测服务

```bash
cd anomaly-detection-service
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

---

## 📞 技术支持

如有问题，请查看：
- 服务日志：`docker-compose logs -f`
- Swagger API 文档：http://localhost:8080/swagger-ui.html
- 异常检测 API 文档：http://localhost:8001/api/docs

---

## 📄 许可证

本项目为毕业设计项目，仅供学习和研究使用。

---

**最后更新：** 2026-05-31
