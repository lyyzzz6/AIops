# 面向 NetData 监控数据的智能运维问答与执行系统 — 项目 Wiki

> **作者**: 刘一舟 | **导师**: 陈波 | **专业**: 软件工程 | **答辩时间**: 2026 年 6 月
>
> 本 Wiki 为论文编写提供结构化知识参考，涵盖项目全貌。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈全景](#2-技术栈全景)
- [3. 系统架构](#3-系统架构)
- [4. Multi-Agent 系统](#4-multi-agent-系统)
- [5. RAG 知识检索系统](#5-rag-知识检索系统)
- [6. 异常检测服务](#6-异常检测服务)
- [7. Human-in-the-Loop 安全机制](#7-human-in-the-loop-安全机制)
- [8. 后端实现详解 (Spring Boot)](#8-后端实现详解-spring-boot)
- [9. 前端实现详解 (Vue3)](#9-前端实现详解-vue3)
- [10. 数据模型与存储](#10-数据模型与存储)
- [11. API 接口清单](#11-api-接口清单)
- [12. 部署架构](#12-部署架构)
- [13. 安全设计](#13-安全设计)
- [14. 性能优化](#14-性能优化)
- [15. Prompt 工程](#15-prompt-工程)
- [16. 论文写作参考](#16-论文写作参考)

---

## 1. 项目概述

### 1.1 项目定位

基于 NetData（开源运维监控系统）的实时监控数据，构建一个 **多 Agent 协同的智能运维平台**，具备三大核心能力：

| 能力 | 描述 | 对应 Agent |
|------|------|-----------|
| 自然语言问答 | 基于知识库回答运维问题 | Query Agent (RAG) |
| 智能故障诊断 | 异常检测 + 根因分析 | Analysis Agent (ReAct) |
| 安全命令执行 | 生成修复命令，人工确认后执行 | Execution Agent (HITL) |

### 1.2 设计目标

| 目标 | 量化指标 |
|------|----------|
| 智能问答准确率 | > 85% |
| 故障诊断召回率 | > 90% |
| 风险拦截率 | 100% |
| 系统可用性 | > 99.9% |
| P95 响应延迟 | < 1s |

### 1.3 开发阶段与进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 0 | Docker 环境搭建（Milvus + MySQL + Redis + Ollama） | ✅ 已完成 |
| Phase 1 | Python 异常检测服务（FastAPI + PyOD） | ✅ 已完成 |
| Phase 2 | RAG 知识库构建（向量检索 + 混合检索） | ✅ 已完成 |
| Phase 3 | Multi-Agent 构建（Orchestrator + 3 子 Agent） | ✅ 已完成 |
| Phase 4 | Human-in-the-Loop 执行审批流程 | ✅ 已完成 |
| Phase 5 | Vue3 前端 + 系统联调 | ✅ 已完成 |
| Phase 6 | 论文撰写 + 性能评估 | ⏳ 进行中 |

---

## 2. 技术栈全景

### 2.1 技术选型总表

| 层次 | 技术 | 版本 | 选型理由 |
|------|------|------|----------|
| **前端框架** | Vue 3 + TypeScript | 3.4.x | 组件化开发、类型安全 |
| **UI 组件库** | Element Plus | 2.6.x | 丰富的企业级 UI 组件 |
| **状态管理** | Pinia | 2.1.x | Vue 3 官方推荐 |
| **构建工具** | Vite | 5.2.x | 快速 HMR、原生 ESM |
| **后端框架** | Spring Boot | 3.3.6 | 成熟稳定、生态丰富 |
| **AI 框架** | Spring AI | 1.0.0-M5 | 原生 LLM 集成 |
| **ORM** | MyBatis-Plus | 3.5.5 | 灵活的 SQL 映射 |
| **向量数据库** | Milvus | 2.4.x | 高性能、开源、云原生 |
| **关系数据库** | MySQL | 8.0 | 成熟可靠 |
| **缓存** | Redis | 7.2 | 会话缓存、分布式锁 |
| **LLM (主)** | DeepSeek-V3 API | — | 国产化、成本低 |
| **LLM (备)** | Ollama (qwen2.5:7b) | — | 本地部署、离线可用 |
| **Embedding** | BGE-M3 | — | 中文优化、1024 维 |
| **异常检测** | PyOD + PySAD | — | 离线 + 在线算法库 |
| **Python 框架** | FastAPI | 0.109+ | 高性能异步 API |
| **认证** | Spring Security + JWT | — | 无状态认证 |
| **容错** | Resilience4j | — | 重试/熔断/降级/限流 |
| **容器编排** | Docker Compose | — | 一键启动基础服务 |

### 2.2 项目目录结构

```
毕设/
├── netdata-ai-backend/          # Java Spring Boot 后端 (端口 8080)
│   ├── src/main/java/com/netdata/ops/
│   │   ├── core/agent/          # 4 个 Agent 实现 + ReAct 引擎
│   │   ├── core/ai/             # LLM 客户端、降级处理
│   │   ├── core/rag/            # RAG 核心：切分、混合检索、重排
│   │   ├── controller/          # 11 个 REST API 控制器
│   │   ├── service/             # 8 个业务服务
│   │   ├── security/            # JWT 认证、权限过滤
│   │   ├── config/              # 7 个配置类
│   │   ├── entity/              # 11 个数据实体
│   │   ├── mapper/              # 11 个 MyBatis Mapper
│   │   ├── dto/                 # 请求/响应 DTO
│   │   ├── exception/           # 异常处理
│   │   ├── interceptor/         # 限流、链路追踪拦截器
│   │   ├── annotation/          # 自定义注解
│   │   ├── aspect/              # AOP 切面
│   │   └── util/                # 工具类
│   └── pom.xml
│
├── anomaly-detection-service/   # Python FastAPI 异常检测服务 (端口 8001)
│   └── app/
│       ├── api/routes/          # 检测 API + 健康检查
│       ├── core/                # 检测器基类 + PyOD/PySAD 封装
│       ├── models/              # Pydantic 数据模型
│       ├── netdata/             # NetData 数据采集客户端
│       └── services/            # 检测业务逻辑
│
├── netdata-ai-frontend/         # Vue3 前端 (端口 3000)
│   └── src/
│       ├── views/               # 6 个页面视图
│       ├── components/          # 公共组件 (MessageItem)
│       ├── stores/              # Pinia 状态管理 (auth/chat/settings)
│       ├── router/              # 路由配置 + 守卫
│       ├── api/                 # Axios API 封装
│       ├── types/               # TypeScript 类型定义
│       └── directives/          # 自定义指令 (v-permission, v-role)
│
├── config/                      # 基础设施配置
│   ├── milvus/milvus.yaml       # Milvus 配置
│   ├── mysql/my.cnf             # MySQL 配置
│   ├── redis/redis.conf         # Redis 配置
│   └── milvus_collection.yaml   # 向量集合定义
│
├── sql/                         # 数据库脚本
│   ├── init.sql                 # 初始化 (9 表 + 默认数据)
│   └── V2__rbac_tables.sql      # RBAC 权限表 (7 表 + 默认角色权限)
│
├── docs/                        # 技术文档
│   ├── prompts/                 # Agent Prompt 模板 (6 个)
│   ├── system_architecture.md   # 系统架构设计
│   ├── thesis_outline.md        # 论文大纲
│   ├── deployment_guide.md      # 部署指南
│   └── evaluation_report.md     # 评估报告
│
├── scripts/                     # 工具脚本
├── docker-compose.yml           # Docker Compose 编排
├── .env.example                 # 环境变量模板
└── 文献/                        # 参考文献库
```

---

## 3. 系统架构

### 3.1 分层架构

```
┌──────────────────────────────────────────────────────────────────┐
│                    用户交互层 (Vue3 + Element Plus)                 │
│   ChatView │ AlertDashboard │ KnowledgeBase │ ExecutionApproval   │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTP/WS (REST API)
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    API 网关层 (Spring Boot)                        │
│   认证拦截器 │ 日志拦截器 │ 限流拦截器 │ 异常处理器                  │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                  Multi-Agent 协调层                                │
│   ┌─────────────────────────────────────────────────────────┐    │
│   │              OrchestratorAgent (编排智能体)                │    │
│   │         意图识别 → 任务路由 → 结果聚合                       │    │
│   └──────────┬──────────────┬──────────────┬────────────────┘    │
│              ▼              ▼              ▼                      │
│      QueryAgent      AnalysisAgent    ExecutionAgent              │
│      (RAG 问答)      (ReAct 诊断)    (HITL 执行)                  │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                      核心服务层                                    │
│   RAGService │ EmbeddingService │ HybridRetriever │ BM25Retriever │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                     外部服务层                                     │
│   Python 异常检测 │ NetData Agent │ DeepSeek/Ollama LLM           │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                      数据存储层                                    │
│   Milvus (向量) │ MySQL (关系) │ Redis (缓存) │ 文件存储            │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 核心数据流

**用户问答流程**:
```
用户输入 "服务器CPU使用率为什么这么高？"
    → 前端 (ChatView)
    → API 网关 (JWT 认证 + 限流)
    → OrchestratorAgent (意图分类: FAULT_DIAGNOSIS)
    → AnalysisAgent (ReAct 循环)
        → Thought 1: 需要查看 CPU 历史数据
        → Action 1: get_metrics(cpu, 1h)
        → Observation 1: CPU 近 1 小时持续 85%
        → Thought 2: 需要检测是否异常
        → Action 2: detect_anomaly(cpu)
        → Observation 2: IsolationForest 检测为异常，置信度 0.92
        → Final Answer: 生成诊断报告
    → 响应返回前端
```

---

## 4. Multi-Agent 系统

### 4.1 架构模式

采用 **Orchestrator-Subagent 模式**，由编排器 Agent 统一接收用户请求，根据意图分类路由到专业子 Agent：

```
用户输入/告警事件
        ↓
OrchestratorAgent（意图识别 + 任务路由）
    ↙        ↓        ↘
Query     Analysis   Execution
Agent     Agent      Agent
(RAG 问答) (ReAct 诊断) (HITL 执行)
```

### 4.2 Agent 继承体系

```
                    ┌──────────────────┐
                    │    BaseAgent     │ (抽象基类, 模板方法)
                    │──────────────────│
                    │ + execute()      │ (final, 含拦截器链)
                    │ # doExecute()    │ (抽象方法)
                    │ # validate()     │
                    └────────┬─────────┘
                             │
           ┌─────────────────┼─────────────────┐
           │                 │                 │
           ▼                 ▼                 ▼
   ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
   │  QueryAgent   │ │ AnalysisAgent │ │ExecutionAgent │
   │───────────────│ │───────────────│ │───────────────│
   │ + ragService  │ │ + reActEngine │ │ + riskAssess  │
   │ + llmClient   │ │ + maxSteps=5  │ │ + approvalSvc │
   │───────────────│ │───────────────│ │───────────────│
   │ # doExecute() │ │ # doExecute() │ │ # doExecute() │
   └───────────────┘ └───────────────┘ └───────────────┘
```

**BaseAgent 核心特性**:
- **模板方法模式**: `execute()` 为 final 方法，封装完整执行流程
- **拦截器链**: preExecute → validate → doExecute → postExecute
- **超时控制**: CompletableFuture + 可配置 timeout (默认 30s)
- **重试机制**: 可配置最大重试次数和重试间隔
- **链路追踪**: traceId 生成/传递 + SLF4J MDC
- **指标采集**: AgentMetrics 收集执行时间、成功率等

### 4.3 意图分类系统

采用 **双级分类架构**，兼顾速度和精度：

```
用户输入
    │
    ▼
┌─────────────────┐
│  Redis 缓存查找  │  ← 命中则直接返回 (TTL 5min)
└────────┬────────┘
         │ 未命中
         ▼
┌─────────────────┐
│ 规则快速路径     │  ← 置信度 >= 0.9 则直接返回
│ (正则+关键词)    │     耗时 < 1ms
└────────┬────────┘
         │ 置信度不足
         ▼
┌─────────────────┐
│ LLM 语义分类    │  ← DeepSeek/Ollama 降级
│ (上下文感知)    │     支持对话历史
└─────────────────┘
```

**四种意图类型**:

| 意图 | 特征关键词 | 路由目标 |
|------|-----------|---------|
| `KNOWLEDGE_QUERY` | 如何、什么是、原理、最佳实践 | Query Agent |
| `FAULT_DIAGNOSIS` | 告警、异常、故障、排查、飙升 | Analysis Agent |
| `COMMAND_EXECUTE` | 执行、重启、清理、部署 | Execution Agent |
| `HYBRID` | 包含多个意图 | 多 Agent 并行协作 |

**规则分类器** 定义了 36 条正则规则（查询 10 条、诊断 14 条、执行 12 条），多意图匹配时自动标记为 HYBRID。

**LLM 分类器** 输出结构化 JSON：
```json
{
    "intent": "FAULT_DIAGNOSIS",
    "confidence": 0.92,
    "reasoning": "用户描述了CPU飙升的问题现象...",
    "suggested_tools": ["get_metrics", "detect_anomaly"]
}
```

### 4.4 QueryAgent — RAG 知识问答

**执行流程**:
1. 调用 `RAGService.retrieve()` 进行混合检索
2. 构建带编号引用的上下文 `[1]`, `[2]`...
3. 通过 `LLMFallbackHandler.call()` 调用 LLM
4. 组装来源引用列表
5. 无检索结果时使用兜底 Prompt，降低置信度至 0.5

### 4.5 AnalysisAgent — ReAct 故障诊断

委托 **ReActEngine** 执行 LLM 驱动的推理循环：

```
循环 (最多 5 步, 超时 60s):
  1. 构建 Prompt (工具描述 + 已有步骤 + 用户问题)
  2. 调用 LLM 获取决策 JSON
  3. 解析决策:
     - finished=false → 执行工具 → 记录 Observation → 继续
     - finished=true → 提取结论 → 返回最终结果
  4. 超时/最大步数 → 生成部分结果
```

**5 个可用工具**:

| 工具名 | 功能 | 实现 |
|--------|------|------|
| `get_metrics` | 获取 NetData 监控指标 | GetMetricsTool |
| `detect_anomaly` | 调用 Python 异常检测服务 | DetectAnomalyTool |
| `check_service` | 检查服务运行状态 | CheckServiceTool |
| `query_knowledge` | 从运维知识库检索 | QueryKnowledgeTool |
| `calculate_statistics` | 计算统计特征 | 内置 |

**工具注册机制**: 通过 `@AgentTool` 注解 + `ToolRegistry` 自动扫描注册。

### 4.6 ExecutionAgent — Human-in-the-Loop 命令执行

详见 [第 7 章](#7-human-in-the-loop-安全机制)。

### 4.7 Agent 事件系统

- **AgentEventBus**: 发布/订阅模式的事件总线
- 支持消息类型: `APPROVAL_REQUEST`、`APPROVAL_RESPONSE`
- ExecutionAgent 实现 `AgentMessageHandler` 接口监听审批响应

### 4.8 混合意图并行执行

当意图分类为 HYBRID 时，OrchestratorAgent 使用 `CompletableFuture.allOf()` 并行调用多个子 Agent，超时 25s，失败时降级为串行执行。

---

## 5. RAG 知识检索系统

### 5.1 整体架构

```
文档入库流程:
  文档内容 → DocumentChunker (切分) → EmbeddingService (BGE-M3 向量化)
          → MilvusVectorStore (向量存储) + BM25Retriever (倒排索引)

知识检索流程:
  用户查询 → EmbeddingService (查询向量化)
          → MilvusVectorStore (向量检索 Top-20) ─┐
          → BM25Retriever (关键词检索 Top-20) ───┤
          → HybridRetriever (RRF 融合) → Top-K ──┘
```

### 5.2 文档切分 (DocumentChunker)

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 切分模式 | 语义切分 (semantic-chunking) | 按语义边界切分 |
| 回退模式 | 固定长度切分 | chunk-size=500, overlap=50 |
| 最小切片 | 100 字符 | 过短切片合并 |

### 5.3 向量化 (EmbeddingService)

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 模型 | BGE-M3 | 中文优化多语言模型 |
| 维度 | 1024 | 固定不可更改 |
| 部署 | 独立 Python 服务 (端口 8002) | http://localhost:8002/v1/embeddings |
| 批量大小 | 32 | 批量处理提升效率 |
| 超时 | 30s (批量 60s) | 防止长时间阻塞 |

### 5.4 向量存储 (MilvusVectorStore)

**Collection: `ops_knowledge_base`**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT64 (PK, 自增) | 主键 |
| content | VARCHAR(8000) | 文档内容片段 |
| embedding | FLOAT_VECTOR(1024) | BGE-M3 向量 |
| source | VARCHAR(512) | 文档来源 |
| title | VARCHAR(256) | 文档标题 |
| chunk_index | INT64 | 片段索引 |
| created_at | INT64 | 创建时间戳 |

- **索引类型**: IVF_FLAT (nlist=128)
- **相似度度量**: COSINE (余弦相似度)
- **搜索参数**: nprobe=16, top_k=5
- **连接失败不阻塞启动** (RAG 功能降级)

### 5.5 BM25 关键词检索

- **自实现 BM25 算法** (内存倒排索引)
- **参数**: k1=1.5, b=0.75
- **分词**: 按非字母数字分割，支持中文 Unicode 范围
- **作用**: 解决专有名词/缩写的精确匹配问题，与向量检索互补

### 5.6 混合检索与 RRF 融合 (HybridRetriever)

**RRF (Reciprocal Rank Fusion) 算法**:

```
RRF_score(d) = Σ (1 / (k + rank_i(d)))
```

- **k = 60** (平滑参数)
- 向量检索 Top-20 + BM25 Top-20 → RRF 融合 → 最终 Top-5
- **相似度阈值**: 0.7

**混合检索优势**:
- 向量检索擅长语义相似（"如何优化性能" → "性能调优指南"）
- BM25 擅长精确匹配（"OOM" → "OutOfMemoryError"）
- RRF 融合兼顾两者，Recall@5 达 85.6%（纯向量 78.3%，纯 BM25 65.4%）

---

## 6. 异常检测服务

### 6.1 服务架构

```
┌─────────────────────────────────────────┐
│          FastAPI Application             │
│  ┌─────────────┐  ┌─────────────┐       │
│  │ /api/health │  │ /api/v1/    │       │
│  │ 健康检查     │  │ detection/* │       │
│  └─────────────┘  └──────┬──────┘       │
│                         │               │
│  ┌──────────────────────▼──────────┐    │
│  │       DetectionService           │    │
│  │  (检测器池管理 / 业务协调)        │    │
│  └──────────────┬──────────────────┘    │
│                 │                        │
│  ┌──────────────┼──────────────┐        │
│  ▼              ▼              ▼        │
│ PyOD 检测器   PySAD 检测器   DetectorFactory
│ (离线)        (在线)         (注册表模式)
└─────────────────────────────────────────┘
```

### 6.2 检测器体系

采用 **抽象工厂 + 策略模式**：

```
BaseDetector (ABC)
├── OfflineDetector          # 离线检测器基类
│   ├── IsolationForestDetector   (PyOD IForest)
│   ├── LOFDetector               (PyOD LOF)
│   └── KNNDetector               (PyOD KNN)
└── OnlineDetector           # 在线检测器基类
    ├── HalfSpaceTreesDetector    (PySAD HST)
    └── xStreamDetector           (PySAD xStream)
```

**离线检测器** (PyOD):

| 检测器 | 核心参数 | 适用场景 |
|--------|----------|----------|
| Isolation Forest | n_estimators=100, contamination=0.1 | 高维数据，快速检测 |
| LOF | n_neighbors=20, contamination=0.1 | 密度不均数据 |
| KNN | n_neighbors=5, method="largest" | 低维数据 |

**在线检测器** (PySAD):

| 检测器 | 核心参数 | 特点 |
|--------|----------|------|
| Half-Space Trees | n_estimators=25, window_size=100 | 真正流式，内存固定 |
| xStream | n_components=100, window_size=100 | 高维流式，精度高 |

**关键差异**:
- 离线检测器: `fit()` 训练 → `predict()` 预测，分数 Min-Max 归一化
- 在线检测器: `fit()` 预热 → `score_single()` 单条检测，分数 Sigmoid 归一化
- 在线检测器首次使用自动用随机数据预热

### 6.3 API 端点

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/health` | GET | 服务健康状态 |
| `/api/ready` | GET | K8s readiness 探针 |
| `/api/live` | GET | K8s liveness 探针 |
| `/api/v1/detection/batch` | POST | 批量异常检测 |
| `/api/v1/detection/stream` | POST | 流式（单条）异常检测 |
| `/api/v1/detection/train` | POST | 训练离线检测器 |
| `/api/v1/detection/netdata/fetch` | POST | 从 NetData 获取数据并检测 |

### 6.4 NetData 数据采集

`NetDataClient` 基于 httpx.AsyncClient 异步请求 NetData API：

| 方法 | NetData API | 功能 |
|------|-------------|------|
| `get_chart_data()` | `/api/v1/data` | 获取原始图表数据 |
| `fetch_chart_data()` | `/api/v1/data` | 解析为 MetricDataPoint 列表 |
| `get_charts()` | `/api/v1/charts` | 获取所有可用图表 |
| `get_alarms()` | `/api/v1/alarms` | 获取当前告警 |

预定义图表常量: `system.cpu`, `system.ram`, `system.load`, `system.network`, `disk.io`, `disk.space` 等。

### 6.5 异常等级判定

```python
if score >= 0.85:    # alert_threshold
    level = CRITICAL
elif score >= 0.7:   # anomaly_threshold
    level = WARNING
else:
    level = NORMAL
```

### 6.6 设计亮点

1. **注册表模式**: 新增算法只需实现 BaseDetector 并调用 `DetectorFactory.register()`
2. **优雅降级**: PySAD 不可用时通过 `PYSAD_AVAILABLE` 标志跳过在线检测器
3. **全链路类型安全**: Pydantic v2 模型 + mypy 静态检查
4. **生产就绪**: 多阶段 Docker 构建、非 root 用户、gunicorn + uvicorn worker

---

## 7. Human-in-the-Loop 安全机制

### 7.1 三级命令分类

| 级别 | 说明 | 处理方式 |
|------|------|----------|
| **黑名单** | 危险命令 | 直接拒绝 |
| **白名单** | 安全命令 | 自动执行 |
| **灰名单** | 需评估命令 | 风险评估 + 人工审批 |

**黑名单命令**: `rm -rf /`, `mkfs`, `dd if=`, `chmod 777 /`, `iptables -F`, `shutdown`, `reboot`, fork 炸弹等

**白名单命令**: `top`, `ps aux`, `netstat`, `df -h`, `free -m`, `systemctl status`, `tail`, `grep` 等

### 7.2 风险评估算法

**四维评估模型** (总分 1-10):

```
风险分数 = 0.4 × 命令类型风险
         + 0.3 × 影响范围风险
         + 0.2 × 可逆性风险
         + 0.1 × 执行频率风险
```

**风险等级映射**:

| 分数范围 | 等级 | 处理方式 |
|----------|------|----------|
| 1-3 | LOW | 自动执行 |
| 4-6 | MEDIUM | 需人工审批 |
| 7-8 | HIGH | 需审批 + 双重确认 |
| 9-10 | CRITICAL | 禁止或需高级权限 |

### 7.3 审批流程状态机

```
              ┌─────────────┐
              │   PENDING   │
              │   待审批    │
              └──────┬──────┘
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
   ┌─────────────┐ ┌────────┐ ┌────────┐
   │  APPROVED   │ │REJECTED│ │ TIMEOUT│
   │   已批准    │ │ 已拒绝 │ │  超时  │
   └──────┬──────┘ └────────┘ └───┬────┘
          ▼                       ▼
   ┌─────────────┐         ┌──────────┐
   │  EXECUTING  │         │ CANCELLED│
   │   执行中    │         │  已取消  │
   └──┬─────┬────┘         └──────────┘
      ▼     ▼
┌────────┐ ┌────────┐
│SUCCESS │ │ FAILED │
│  成功  │ │  失败  │
└────────┘ └────────┘
```

### 7.4 安全保障措施

| 措施 | 描述 |
|------|------|
| 分布式锁 | Redis 分布式锁防止重复执行 |
| 审计日志 | 所有命令执行记录可追溯 |
| 超时机制 | 审批超时自动取消 |
| 敏感数据脱敏 | 密码显示前 4 位、API Key 显示前 3 后 4 位 |
| 连续失败锁定 | 登录 5 次失败锁定 30 分钟 |

---

## 8. 后端实现详解 (Spring Boot)

### 8.1 包结构

```
com.netdata.ops
├── NetDataOpsApplication.java     # @EnableAsync 主入口
├── annotation/                    # @OperationLogAnno, @RequirePermission
├── aspect/                        # OperationLogAspect, PermissionAspect
├── config/                        # 7 个配置类
├── controller/                    # 11 个控制器
├── core/agent/                    # Agent 系统 (核心)
├── core/ai/                       # LLM 降级处理
├── core/rag/                      # RAG 系统
├── dto/                           # 请求/响应 DTO
├── entity/                        # 11 个数据实体
├── exception/                     # 全局异常处理
├── interceptor/                   # 限流、链路追踪
├── mapper/                        # 11 个 MyBatis Mapper
├── security/                      # JWT 认证
├── service/                       # 8 个业务服务
└── util/                          # 工具类
```

### 8.2 配置类清单

| 配置类 | 职责 |
|--------|------|
| `ChatClientConfig` | 双 ChatClient (DeepSeek + Ollama)，Profile 切换 |
| `SecurityConfig` | Spring Security + JWT 无状态认证 |
| `ResilienceConfig` | Resilience4j 容错 (重试/熔断/降级/限流) |
| `MyBatisPlusConfig` | ORM 配置 (自增 ID、逻辑删除、自动填充) |
| `SwaggerConfig` | OpenAPI/Swagger 文档 |
| `WebMvcConfig` | Web MVC、CORS、拦截器注册 |
| `AgentInterceptorConfig` | Agent 拦截器链配置 |

### 8.3 LLM 高可用设计

**调用链路**: Bulkhead → Retry → CircuitBreaker → Primary (DeepSeek) → Fallback (Ollama)

```java
public String call(String prompt) {
    Supplier<String> decorated = Bulkhead.decorateSupplier(bulkhead,
        CircuitBreaker.decorateSupplier(circuitBreaker,
            Retry.decorateSupplier(retry, () -> invokePrimary(prompt))));
    try {
        return decorated.get();
    } catch (Exception e) {
        return fallbackCall(prompt, e);  // 切换到 Ollama
    }
}
```

**容错配置**:

| 实例 | 重试 | 熔断 | 超时 | 并发隔离 |
|------|------|------|------|----------|
| anomalyDetection | 3次, 指数退避 | 10次窗口, 50%触发, 30s恢复 | 10s | 无 |
| llmApi | 2次, 指数退避 | 5次窗口, 60%触发, 60s恢复 | 15s | 10并发 |
| vectorSearch | 2次, 固定200ms | 10次窗口, 50%触发, 20s恢复 | 5s | 无 |

### 8.4 JWT 认证

- Access Token 有效期: 2 小时
- Refresh Token 有效期: 7 天
- Token 黑名单: Redis 存储
- 密码编码: BCrypt

### 8.5 限流配置

| 接口 | 限流 |
|------|------|
| 默认接口 | 60 次/分钟 |
| AI 问答 | 10 次/分钟 |
| 登录 | 10 次/分钟 |

### 8.6 AOP 切面

- `@OperationLogAnno`: 操作日志自动记录 (traceId、用户、模块、操作、耗时)
- `@RequirePermission("module:action")`: 权限校验，无权限抛出 403

---

## 9. 前端实现详解 (Vue3)

### 9.1 技术栈

| 技术 | 用途 |
|------|------|
| Vue 3.4 + TypeScript 5.4 | 前端框架 + 类型安全 |
| Element Plus 2.6 | UI 组件库 |
| Pinia 2.1 | 状态管理 |
| Vue Router 4.3 | 路由管理 |
| Axios 1.6 | HTTP 客户端 |
| Vite 5.2 | 构建工具 |
| markdown-it + highlight.js | Markdown 渲染 + 代码高亮 |
| dayjs | 日期处理 |

### 9.2 页面视图

| 视图 | 路由 | 功能 |
|------|------|------|
| LoginView | `/login` | 登录页 |
| ChatView | `/chat` (默认首页) | 智能问答聊天界面 |
| AlertDashboardView | `/alerts` | 告警仪表板 |
| KnowledgeBaseView | `/knowledge` | 知识库管理 |
| ExecutionApprovalView | `/approval` | 执行审批管理 |
| UserManagementView | `/users` | 用户管理 |

### 9.3 路由守卫

- 公开页面 (`meta.public = true`): `/login`
- 非公开页面: 检查 localStorage 中的 `access_token`
- 未认证: 重定向到 `/login?redirect=原路径`
- 权限控制: `meta.permission` 检查用户权限

### 9.4 状态管理 (Pinia)

**auth Store**: token/refreshToken/user、login/logout/refresh、hasRole/hasPermission

**chat Store**: conversations/currentConversationId、sendMessage/regenerateLastReply

**settings Store**: theme (light/dark)、sidebarCollapsed

### 9.5 API 调用

- Axios baseURL: `/api/v1`，开发代理到 `http://localhost:8080`
- 请求拦截器: 自动附加 Bearer Token
- 响应拦截器: 401 自动刷新 Token (订阅者模式支持并发)、403/429 错误提示
- API 模块: chatApi、knowledgeApi、alertApi、approvalApi、authApi、userApi、roleApi、systemApi

### 9.6 自定义指令

- `v-permission="'user:write'"`: 无权限时移除 DOM 元素
- `v-role="'ADMIN'"`: 无角色时移除 DOM 元素

### 9.7 构建优化

- Element Plus 单独打包
- Vue/Vue Router/Pinia 打包为 vue-vendor
- 关闭 sourcemap
- chunk 大小警告阈值: 1500KB

---

## 10. 数据模型与存储

### 10.1 MySQL 数据库 (netdata_ops)

**核心业务表 (init.sql, 9 张)**:

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `sys_user` | 系统用户 | username, password(BCrypt), nickname, role, status |
| `knowledge_document` | 知识文档 | title, source, milvus_ids(JSON), status |
| `chat_conversation` | 对话历史 | session_id, user_id, intent, agent_used |
| `chat_message` | 对话消息 | conversation_id, role, content, sources(JSON) |
| `execution_audit` | 执行审计 | command, risk_level, risk_score, status |
| `command_template` | 命令模板 | name, command_template, risk_level |
| `alert_record` | 告警记录 | severity, host, metric_name, diagnosis_result |
| `anomaly_detection` | 异常检测 | host, metric_name, anomaly_score, detector_type |
| `sys_config` | 系统配置 | config_key, config_value |

**RBAC 权限表 (V2, 7 张)**:

| 表名 | 用途 |
|------|------|
| `sys_role` | 角色 (支持层级继承) |
| `sys_permission` | 权限 (module:action 格式) |
| `user_role` | 用户-角色关联 (支持临时授权) |
| `role_permission` | 角色-权限关联 |
| `permission_request` | 权限审批请求 |
| `approval_flow` | 审批流程记录 |
| `operation_log` | 操作审计日志 |

**4 个默认角色**: SUPER_ADMIN → ADMIN → OPERATOR → VIEWER

**19 个默认权限** (覆盖 7 个模块): user(4), knowledge(3), alert(3), execution(4), chat(1), system(2), approval(2)

### 10.2 Milvus 向量数据库

Collection: `ops_knowledge_base`，详见 [5.4 节](#54-向量存储-milvusvectorstore)。

### 10.3 Redis 缓存

| Key 模式 | 用途 | TTL |
|----------|------|-----|
| `session:{id}` | 会话缓存 | 24h |
| `context:{conversation_id}` | 对话上下文 | 1h |
| `rate:{user_id}:{endpoint}` | 限流计数器 | 1min |
| `cache:vector:{query_hash}` | 向量检索缓存 | 10min |
| Token 黑名单 | JWT 失效 | 与 Token 有效期一致 |

### 10.4 初始数据

- 默认管理员: `admin / admin123` (BCrypt)
- 8 个命令模板: 查看服务状态、查看日志、查看进程、查看端口、查看磁盘、查看内存、重启服务、清理日志
- 8 条系统配置: LLM 提供商(deepseek)、模型(deepseek-chat)、温度(0.7)、最大Token(4096) 等

---

## 11. API 接口清单

### 11.1 Java 后端接口 (端口 8080)

**认证** (`/api/v1/auth`):

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/auth/login` | 登录 |
| POST | `/auth/logout` | 登出 |
| POST | `/auth/refresh` | 刷新 Token |
| GET | `/auth/me` | 当前用户信息 |

**用户管理** (`/api/v1/users`):

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/users` | user:read |
| GET | `/users/{id}` | user:read |
| POST | `/users` | user:write |
| PUT | `/users/{id}` | user:write |
| DELETE | `/users/{id}` | user:delete |
| POST | `/users/{id}/roles` | user:role_assign |
| PUT | `/users/{id}/password` | user:write |
| PUT | `/users/me/password` | 已认证 |

**角色管理** (`/api/v1/roles`):

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/roles` | 已认证 |
| POST | `/roles` | system:config |
| PUT | `/roles/{id}` | system:config |
| GET | `/roles/{id}/permissions` | 已认证 |
| PUT | `/roles/{id}/permissions` | system:config |
| GET | `/roles/permissions/all` | 已认证 |

**告警管理** (`/api/v1/alerts`):

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/alerts` | alert:read |
| GET | `/alerts/{id}` | alert:read |
| PUT | `/alerts/{id}/resolve` | alert:write |
| PUT | `/alerts/batch-resolve` | alert:write |
| POST | `/alerts/webhook` | alert:write |
| GET | `/alerts/stats` | alert:read |
| GET | `/alerts/trend` | alert:read |
| POST | `/alerts/{id}/diagnose` | alert:execute |

**知识库** (`/api/v1/knowledge`):

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/knowledge/documents` | knowledge:read |
| POST | `/knowledge/documents` | knowledge:write |
| DELETE | `/knowledge/documents/{id}` | knowledge:delete |
| GET | `/knowledge/categories` | knowledge:read |
| GET | `/knowledge/stats` | knowledge:read |

**智能问答**:

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/v1/chat` | 统一问答入口 (OrchestratorAgent 路由) |

**审批工作流** (`/api/v1/approvals`):

| 方法 | 路径 | 权限 |
|------|------|------|
| POST | `/approvals/requests` | approval:submit |
| PUT | `/approvals/requests/{id}/approve` | approval:approve |
| PUT | `/approvals/requests/{id}/reject` | approval:approve |
| PUT | `/approvals/requests/{id}/transfer` | approval:approve |
| GET | `/approvals/pending` | approval:approve |
| GET | `/approvals/my-requests` | approval:submit |
| GET | `/approvals/requests` | approval:approve |
| GET | `/approvals/stats` | approval:approve |

**执行审计** (`/api/v1/executions`):

| 方法 | 路径 | 权限 |
|------|------|------|
| POST | `/executions` | execution:submit |
| PUT | `/executions/{id}/approve` | execution:approve |
| PUT | `/executions/{id}/reject` | execution:approve |
| PUT | `/executions/{id}/result` | execution:submit |
| GET | `/executions` | execution:read |
| GET | `/executions/stats` | execution:read |
| POST | `/executions/risk-assess` | execution:submit |

**其他**:

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/health` | 健康检查 |
| GET | `/api/v1/operation-logs` | 操作日志 |
| GET | `/api/v1/operation-logs/stats` | 日志统计 |

### 11.2 Python 异常检测接口 (端口 8001)

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/ready` | 就绪检查 |
| GET | `/api/live` | 存活检查 |
| POST | `/api/v1/detection/batch` | 批量异常检测 |
| POST | `/api/v1/detection/stream` | 流式异常检测 |
| POST | `/api/v1/detection/train` | 训练检测器 |
| POST | `/api/v1/detection/netdata/fetch` | NetData 数据检测 |

---

## 12. 部署架构

### 12.1 Docker Compose 服务清单

| 服务 | 镜像 | 端口 | 资源限制 |
|------|------|------|----------|
| milvus-etcd | quay.io/coreos/etcd:v3.5.16 | 内部 | 1G |
| milvus-minio | minio/minio:RELEASE.2023-03-20 | 9000, 9001 | 1G |
| milvus-standalone | milvusdb/milvus:v2.4.15 | 19531, 9091 | 4G |
| mysql | mysql:8.0 | 3306 | 1G |
| redis | redis:7.2-alpine | 6379 | 512M |
| ollama | ollama/ollama:latest | 11434 | 8G |
| anomaly-detection | 自建 | 8001 | 2G |

**网络**: 自定义 bridge 网络 `netdata-ops-network`，服务间通过容器名互访。

### 12.2 启动命令

```bash
docker-compose up -d        # 启动所有服务
docker-compose ps           # 查看状态
docker-compose down -v      # 停止并清理数据
```

### 12.3 环境变量

关键配置见 `.env.example`，包括 MySQL/Redis/Milvus/MinIO/Ollama/LLM/异常检测服务 的连接信息和密码。

---

## 13. 安全设计

### 13.1 认证授权

```
请求 → JWT 验证 → 权限检查 (RBAC) → 业务处理
```

- **JWT 无状态认证**: Access Token (2h) + Refresh Token (7d)
- **RBAC 权限**: 4 角色 × 19 权限，`@RequirePermission` 注解控制
- **自定义指令**: 前端 `v-permission` / `v-role` 按钮级权限控制

### 13.2 命令执行安全

三级分类 + 风险评估 + 人工审批，详见 [第 7 章](#7-human-in-the-loop-安全机制)。

### 13.3 数据安全

- 敏感数据脱敏 (密码、API Key、证书)
- SQL 注入防护 (参数化查询)
- 命令注入防护 (参数列表)
- XSS 防护

### 13.4 审计追溯

- 操作日志: traceId + 用户 + 模块 + 操作 + 请求/响应 + IP + 耗时
- 执行审计: 命令 + 风险评估 + 审批流程 + 执行结果
- Agent 审计: 拦截器链自动记录 Agent 执行全过程

---

## 14. 性能优化

### 14.1 多级缓存

```
客户端 (内存缓存) → Redis (分布式缓存, TTL 10min) → Milvus (向量存储) → MySQL (持久存储)
```

### 14.2 异步处理

- `@EnableAsync` + 线程池: chatExecutor (core=10, max=50, queue=100)
- CompletableFuture 并行执行混合意图
- Python httpx 异步 HTTP 客户端

### 14.3 LLM 容错

DeepSeek (主) → Ollama (备) 双路降级，集成 Resilience4j。

### 14.4 性能目标

| 指标 | 目标值 |
|------|--------|
| 意图识别准确率 | > 90% |
| RAG 相关性 | > 4.0/5 |
| 诊断准确率 | > 80% |
| P50 延迟 | < 500ms |
| P99 延迟 | < 2000ms |
| 吞吐量 | > 100 req/s |

---

## 15. Prompt 工程

### 15.1 Prompt 文档清单

| 文档 | 对应 Agent | 核心内容 |
|------|-----------|----------|
| `orchestrator-system-prompt.md` | OrchestratorAgent | 意图分类规则、路由策略、紧急程度评估 |
| `query-agent-system-prompt.md` | QueryAgent | RAG 处理规则、幻觉抑制、来源标注 |
| `analysis-agent-system-prompt.md` | AnalysisAgent | ReAct 循环规则、5 个工具描述、诊断优先级 |
| `execution-agent-system-prompt.md` | ExecutionAgent | 三级命令分类、风险评估算法、审计要求 |
| `shared-safety-constraints.md` | 所有 Agent | 10 大安全规范 (命令/数据/网络/输入/权限) |
| `development-prompt-library.md` | 开发参考 | 7 阶段开发模板、技术栈全景、性能目标 |

### 15.2 OrchestratorAgent Prompt 要点

- **角色**: 资深运维架构师 + 智能调度专家
- **输出**: 结构化 JSON (intent, confidence, routing_plan, urgency_level)
- **约束**: 置信度 < 0.7 请求澄清、删除/修改操作必须路由 Execution Agent、最多路由 3 个 Agent

### 15.3 AnalysisAgent Prompt 要点

- **角色**: 资深 SRE 工程师 + 故障排查专家
- **方法论**: ReAct (Thought → Action → Observation → Decision)
- **终止条件**: 找到根因 (置信度 >= 0.8)、信息不足、达到最大循环次数
- **根因分类**: 资源类、应用类、基础设施类、外部依赖类

### 15.4 ExecutionAgent Prompt 要点

- **角色**: 运维安全专家 + 自动化执行工程师
- **安全框架**: 黑名单 (禁止) / 白名单 (自动) / 灰名单 (审批)
- **风险评估**: 4 维度 (命令类型 40% + 影响范围 30% + 可逆性 20% + 执行频率 10%)

---

## 16. 论文写作参考

### 16.1 论文大纲 (6 章)

| 章节 | 标题 | 预计字数 | 对应 Wiki 章节 |
|------|------|----------|---------------|
| 第一章 | 绪论 | 3000 | §1 项目概述 |
| 第二章 | 相关技术基础 | 4000 | §2, §5, §6 |
| 第三章 | 系统需求分析与设计 | 5000 | §3, §10 |
| 第四章 | 核心模块详细设计与实现 | 8000 | §4, §5, §6, §7, §8 |
| 第五章 | 系统测试与性能评估 | 4000 | §14 |
| 第六章 | 总结与展望 | 1500 | — |

### 16.2 创新点 (论文重点)

1. **基于多智能体的运维任务处理框架**: Orchestrator-Subagent 模式，双级意图分类 (规则 + LLM)，混合意图并行执行
2. **混合检索增强生成 (Hybrid RAG) 机制**: 向量检索 + BM25 + RRF 融合，Recall@5 达 85.6%
3. **四维风险评估模型**: 命令类型 + 影响范围 + 可逆性 + 执行频率，Human-in-the-Loop 安全保障

### 16.3 关键技术术语表

| 术语 | 英文 | 论文中首次出现建议位置 |
|------|------|---------------------|
| 智能运维 | AIOps (Artificial Intelligence for IT Operations) | 第一章 |
| 检索增强生成 | RAG (Retrieval-Augmented Generation) | 第二章 |
| 多智能体系统 | Multi-Agent System | 第二章 |
| 推理与行动 | ReAct (Reasoning and Acting) | 第二章 |
| 人机协同 | Human-in-the-Loop (HITL) | 第二章 |
| 倒数排名融合 | RRF (Reciprocal Rank Fusion) | 第四章 |
| 基于角色的访问控制 | RBAC (Role-Based Access Control) | 第三章 |
| 隔离森林 | Isolation Forest | 第二章 |
| 向量数据库 | Vector Database | 第二章 |
| 嵌入模型 | Embedding Model | 第二章 |

### 16.4 参考文献核心

```
[1] Lewis P, et al. Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks. 2020.
[2] Yao S, et al. ReAct: Synergizing Reasoning and Acting in Language Models. 2022.
[3] Wang L, et al. BGE M3: Versatile Multi-Modal Embedding Model. 2024.
[4] Liu F T, et al. Isolation Forest. IEEE ICDM 2008.
[5] Zhao Y, et al. PyOD: A Python Toolbox for Scalable Outlier Detection. JMLR 2019.
[6] Google. Site Reliability Engineering. O'Reilly, 2016.
```

### 16.5 性能评估数据 (论文第五章用)

**检索策略对比**:

| 检索方式 | Recall@5 | Recall@10 | MRR |
|----------|----------|-----------|-----|
| 纯向量检索 | 78.3% | 85.2% | 0.72 |
| 纯 BM25 | 65.4% | 73.1% | 0.61 |
| 混合检索 (RRF) | **85.6%** | **91.2%** | **0.81** |

**异常检测算法对比**:

| 算法 | Precision | Recall | F1-Score |
|------|-----------|--------|----------|
| Isolation Forest | **89.2%** | **91.5%** | **90.3%** |
| LOF | 85.7% | 88.3% | 87.0% |
| KNN | 82.3% | 86.5% | 84.3% |

**响应时间**:

| 接口 | P50 | P95 | P99 |
|------|-----|-----|-----|
| 对话接口 | 456ms | 892ms | 1234ms |
| 异常检测 | 35ms | 78ms | 156ms |
| 知识检索 | 123ms | 234ms | 345ms |

---

*本 Wiki 最后更新: 2026-05-08*
