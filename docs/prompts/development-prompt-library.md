# 开发阶段 Prompt 模板库

> 本文档包含所有开发阶段的详细 Prompt 模板，VibeCoding 新手可以直接复制使用。

---

## 使用说明

### 快速上手流程

1. 找到对应开发阶段的模板
2. 复制模板内容
3. 替换 `{{变量}}` 为实际值
4. 粘贴给 AI 工具（Qoder/Claude）
5. 检查生成的代码
6. 本地运行验证
7. 记录问题到 Memory

---

## Phase 0：Docker 环境搭建

### 模板 0.1：docker-compose.yml 生成

```markdown
# AI 辅助开发 Prompt - Docker Compose 配置生成

## 🎯 任务目标
为智能运维系统生成生产可用的 docker-compose.yml 配置文件。

## 📚 项目上下文

### 技术栈要求
- Milvus 2.4（向量数据库，standalone 模式）
- MySQL 8.0（关系数据库）
- Redis 7.x（缓存）
- Ollama（本地 LLM 推理）

### 运行环境
- 操作系统：Windows 11
- 容器平台：Docker Desktop
- 项目根目录：e:\One Drive\OneDrive\桌面\毕设

### 关键约束
1. 数据持久化：所有数据持久化到 ./data 目录
2. 端口管理：避免端口冲突
3. 网络隔离：所有服务在同一自定义网络
4. 健康检查：每个服务必须有健康检查
5. 资源限制：设置合理的 CPU 和内存限制
6. 启动顺序：使用 depends_on 和健康检查

### 端口规划
- Milvus: 19530（gRPC）、9091（Metrics）
- MySQL: 3306
- Redis: 6379
- Ollama: 11434

## 📤 输出要求

生成以下文件：
1. docker-compose.yml（主配置）
2. .env 模板
3. config/mysql/my.cnf
4. config/redis/redis.conf
5. scripts/verify-env.sh

每个文件添加详细中文注释，解释配置项作用和选择理由。

## ⚠️ 错误处理
- 端口冲突：提供检测和解决方案
- Milvus 启动失败：内存不足处理
- Ollama 模型下载慢：国内镜像配置

## 💡 学习要点
- Docker Compose 核心概念：Service、Volume、Network
- 最佳实践：环境变量、健康检查、资源限制
```

### 模板 0.2：Milvus 初始化

```markdown
# AI 辅助开发 Prompt - Milvus 向量数据库初始化

## 🎯 任务目标
创建 Milvus Collection，配置向量索引，编写初始化脚本。

## 📚 技术规格
- Milvus 版本：2.4
- Embedding 模型：BGE-M3
- 向量维度：1024（创建后不可更改）
- 相似度度量：COSINE

## Collection 设计
```
Collection: ops_knowledge_base
├── id (INT64, 主键, 自增)
├── content (VARCHAR, 文档内容)
├── embedding (FLOAT_VECTOR, 维度 1024)
├── source (VARCHAR, 文档来源)
├── title (VARCHAR, 文档标题)
├── chunk_index (INT64, 片段索引)
└── created_at (INT64, 时间戳)
```

## 📤 输出要求
1. scripts/init_milvus.py - 初始化脚本
2. config/milvus_collection.yaml - Collection 配置
3. tests/test_milvus_connection.py - 连接测试

包含完整代码、详细注释、错误处理、使用示例。
```

---

## Phase 1：Python 异常检测服务

### 模板 1.1：FastAPI 项目骨架

```markdown
# AI 辅助开发 Prompt - FastAPI 异常检测服务骨架

## 🎯 任务目标
搭建 Python FastAPI 项目骨架，实现 NetData 数据采集和异常检测。

## 📚 技术栈
- 框架：FastAPI 0.109+
- 异常检测：PyOD（离线）+ PySAD（在线流式）
- 数据验证：Pydantic v2
- HTTP 客户端：httpx（异步）
- 日志：loguru
- 测试：pytest

## 📁 目录结构
```
anomaly-detection-service/
├── app/
│   ├── main.py           # 应用入口
│   ├── config.py         # 配置管理
│   ├── api/routes/       # 接口层
│   ├── core/             # 检测器封装
│   ├── netdata/          # 数据采集
│   └── models/           # 数据模型
├── tests/
├── requirements.txt
└── Dockerfile
```

## 📤 输出要求
生成以下核心文件：
1. app/main.py - FastAPI 入口，含中间件配置
2. app/config.py - 配置管理
3. app/core/pyod_detector.py - PyOD 封装
4. app/netdata/client.py - NetData API 客户端

包含：类型注解、文档字符串、错误处理、单元测试示例。

## 💡 学习要点
- FastAPI 依赖注入
- Pydantic 数据验证
- 异步处理最佳实践
- 异常检测算法选择
```

### 模板 1.2：异常检测算法实现

```markdown
# AI 辅助开发 Prompt - 异常检测算法详细实现

## 🎯 任务目标
实现 PyOD 和 PySAD 异常检测算法，包括训练、预测、评估。

## 📚 算法设计

### 离线检测（PyOD）
- IsolationForest：CPU/内存指标
- LOF：网络流量指标

### 在线检测（PySAD）
- HalfSpaceTrees：实时监控

## 📤 输出要求
1. app/core/detector_base.py - 检测器抽象基类
2. app/core/pysad_detector.py - 在线检测器
3. app/services/detection_service.py - 服务层
4. tests/test_detectors.py - 单元测试

## 📊 性能目标
- 单次检测延迟 < 100ms（P99）
- 吞吐量 > 1000 req/s
- 内存占用 < 500MB
```

---

## Phase 2：RAG 知识库构建

### 模板 2.1：文档切分与向量化

```markdown
# AI 辅助开发 Prompt - RAG 文档处理流水线

## 🎯 任务目标
实现文档解析、语义切分、向量化、存储到 Milvus。

## 📚 技术方案

### 语义切分（Semantic Chunking）
- 按句子分割
- 计算相邻句子 embedding 相似度
- 相似度 < 阈值处切分

### Embedding 模型
- BGE-M3（1024 维）
- 本地部署
- 中文效果好

## 📤 输出要求
1. app/core/rag/chunker.py - 文档切分器
2. app/core/rag/embedding_service.py - 向量化服务
3. app/core/rag/document_processor.py - 文档处理流水线
4. app/core/rag/knowledge_base_manager.py - 知识库管理

## 💡 学习要点
- RAG 核心概念：Chunk、Embedding、Retrieval
- 切分策略对比
```

### 模板 2.2：混合检索与 Reranker

```markdown
# AI 辅助开发 Prompt - 混合检索 RAG 实现

## 🎯 任务目标
实现向量检索 + BM25 混合检索，集成 Reranker 重排序。

## 📚 技术方案

### RRF 融合算法
```
RRF_score(d) = Σ (1 / (k + rank_i(d)))
k = 60（平滑参数）
```

### Reranker
- bge-reranker-v2-m3
- 精细化排序

## 📤 输出要求
1. app/core/rag/bm25_retriever.py - BM25 检索器
2. app/core/rag/vector_retriever.py - 向量检索器
3. app/core/rag/hybrid_retriever.py - 混合检索器
4. app/core/rag/rag_service.py - RAG 服务层

## 📊 性能对比
| 检索方式 | 召回率 | 延迟 |
|----------|--------|------|
| 向量检索 | 0.75 | 20ms |
| BM25 | 0.70 | 10ms |
| 混合+Reranker | 0.88 | 100ms |
```

---

## Phase 3：Multi-Agent 构建

### 模板 3.1：Orchestrator Agent 实现

```markdown
# AI 辅助开发 Prompt - Orchestrator Agent 实现

## 🎯 任务目标
实现 Orchestrator Agent，负责意图识别、任务路由、结果汇总。

## 📚 架构设计

### 意图类型
- KNOWLEDGE_QUERY：知识问答
- FAULT_DIAGNOSIS：故障诊断
- COMMAND_EXECUTE：命令执行
- HYBRID：混合意图

### 路由策略
```
KNOWLEDGE_QUERY → QueryAgent
FAULT_DIAGNOSIS → AnalysisAgent
COMMAND_EXECUTE → ExecutionAgent
HYBRID → 多Agent协作
```

## 📤 输出要求
1. BaseAgent.java - Agent 抽象基类
2. OrchestratorAgent.java - 编排器实现
3. prompts/orchestrator/intent-classification.md - 意图分类 Prompt
4. OrchestratorAgentTest.java - 单元测试

## 💡 学习要点
- Agent 设计模式：策略模式、模板方法、责任链
- LLM 调用最佳实践
```

---

## Phase 4：Human-in-the-Loop 审批流程

### 模板 4.1：审批流程核心实现

```markdown
# AI 辅助开发 Prompt - Human-in-the-Loop 审批流程

## 🎯 任务目标
实现安全的命令执行审批流程，包括风险评估、人工审批、审计日志。

## 📚 业务流程

### 状态机
```
命令生成 → 黑名单检查 → 白名单检查 → 风险评估
→ (低风险自动执行 | 高风险等待审批)
```

### 风险评估维度
- 命令类型（40%）
- 影响范围（30%）
- 可逆性（20%）
- 执行频率（10%）

## 📤 输出要求
1. CommandRiskAnalyzer.java - 命令风险分析器
2. ApprovalService.java - 审批流程服务
3. ExecutionAuditLogger.java - 审计日志记录器
4. ApprovalController.java - REST API 控制器

## 💡 学习要点
- Human-in-the-Loop 设计原则
- WebSocket 实时通信
```

### 模板 4.2：前端审批界面

```markdown
# AI 辅助开发 Prompt - Vue3 审批界面实现

## 🎯 任务目标
实现实时审批界面，支持 WebSocket 接收、风险详情、提交决策。

## 📚 组件结构
- ExecutionApprovalView.vue - 主界面
- ApprovalDetail.vue - 详情展示
- ApprovalNotification.vue - 通知弹窗

## 📤 输出要求
生成完整的 Vue 3 组件，包含：
- TypeScript 类型定义
- WebSocket 集成
- Element Plus UI
- 暗色主题

## 💡 学习要点
- Vue 3 Composition API
- WebSocket 实时通信
```

---

## Phase 5：Vue3 前端集成

### 模板 5.1：智能问答聊天界面

```markdown
# AI 辅助开发 Prompt - Vue3 智能问答界面

## 🎯 任务目标
实现类似 ChatGPT 的智能问答界面，支持流式输出、历史记录。

## 📚 界面设计
- 侧边栏：历史对话列表
- 主区域：消息列表 + 输入框
- 功能：Markdown 渲染、代码高亮、来源引用

## 📤 输出要求
1. ChatView.vue - 主界面
2. MessageItem.vue - 消息组件
3. stores/chat.ts - Pinia 状态管理
4. api/chat.ts - API 封装

## 💡 学习要点
- SSE 流式输出
- Markdown 渲染
- Pinia 状态管理
```

---

## Phase 6：论文撰写与性能评估

### 模板 6.1：性能评估实验设计

```markdown
# AI 辅助开发 Prompt - 性能评估实验设计

## 🎯 任务目标
设计性能评估实验，生成评估报告和可视化图表。

## 📚 评估维度

### 功能评估
- 意图识别准确率 > 90%
- RAG 问答相关性 > 4.0（5分制）
- 诊断准确率 > 80%

### 性能评估
- P50 延迟 < 500ms
- P99 延迟 < 2000ms
- 吞吐量 > 100 req/s

## 📤 输出要求
1. evaluation/test_cases.json - 测试用例
2. scripts/run_evaluation.py - 评估脚本
3. reports/evaluation_report.md - 评估报告
4. 论文大纲

## 💡 学习要点
- 实验设计原则
- 论文写作技巧
```

---

## 快速参考

### STAR 原则

```
S - Situation：描述当前上下文和背景
T - Task：明确要完成的任务
A - Action：指定具体的行动方式
R - Result：期望的输出格式和质量标准
```

### 每日工作流程

```
上午：
1. 注入项目上下文到 AI
2. 使用 AI 生成代码/测试
3. 人工 Review + 本地调试
4. 记录问题到 Memory

下午：
1. 修复上午发现的问题
2. 运行测试套件
3. 更新文档
4. 准备明天的 Prompt

晚上：
1. 复盘今日工作
2. 优化 Prompt 模板
3. 更新工作日志
```

---

## 版本信息

- 版本：1.0.0
- 更新时间：2026-04-03
- 维护者：刘一舟
