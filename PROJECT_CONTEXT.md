# 项目上下文交接文档

> 将这个文件粘贴给新的 AI 平台，它就能快速了解项目全貌，无需重复解释。

---

## 我是谁

- 姓名：刘一舟，大四软件工程专业
- 毕设题目：**面向 NetData 监控数据的智能运维问答与执行系统**
- 导师：陈波，答辩时间：2026 年 6 月
- 目标：通过这个毕设学习 Agent 开发，打造面向大厂 Agent 岗的简历核心亮点

---

## 项目定位

基于 NetData（开源运维监控系统）的实时监控数据，构建一个多 Agent 协同的智能运维平台，具备：
1. **自然语言问答**（回答运维问题）
2. **智能故障诊断**（异常检测 + 根因分析）
3. **命令执行**（生成修复命令，人工确认后执行）

---

## 技术栈（已最终确认，不要更改）

| 层次 | 技术 | 版本 | 备注 |
|------|------|------|------|
| 后端框架 | Spring Boot | 3.3.x | Java 主语言 |
| AI 框架 | Spring AI | 1.0.x | 注意用 ChatClient，AiClient 已废弃 |
| 异常检测 | Python FastAPI + PyOD + PySAD | 最新 | Python 微服务单独部署 |
| 向量数据库 | Milvus | 2.4 | Docker 部署 |
| LLM | DeepSeek-V3 API（主）| — | Ollama 本地作为开发调试备用 |
| Embedding | BGE-M3 本地 | — | 维度 1024，早期固定不能更换 |
| 前端 | Vue 3 + Element Plus | 最新 | |
| 关系数据库 | MySQL | 8.0 | |
| 缓存 | Redis | 7.x | |
| 知识图谱 | Neo4j（可选） | 5.x | 作为论文进阶亮点 |
| 容器编排 | Docker Compose | — | 开发环境 |

---

## Agent 架构（已确认）

模式：**Orchestrator-Subagent 模式**

```
用户输入/告警事件
        ↓
Orchestrator Agent（意图识别 + 任务路由）
    ↙        ↓        ↘
Query     Analysis   Execution
Agent     Agent      Agent
(RAG 问答) (ReAct 诊断) (Human-in-Loop 执行)
```

- **Orchestrator Agent**：接收输入，判断意图，路由给子 Agent，汇总结果
- **Query Agent**：走 RAG 流程，回答运维相关问题
- **Analysis Agent**：ReAct 模式，多步工具调用，输出结构化诊断报告
- **Execution Agent**：生成命令 → 风险评估 → 人工审批 → 执行 → 记录

---

## RAG 方案（已确认）

主方案：**混合检索 RAG**

```
用户提问
    ├→ 向量检索（Milvus，BGE-M3 Embedding）
    └→ 关键词检索（BM25）
            ↓
      RRF 融合重排序
            ↓
   bge-reranker-v2-m3 精排
            ↓
      Top-5 注入 Prompt → LLM 生成答案
```

- 文档切分：按语义切分（Semantic Chunking），不用固定长度
- 进阶可选：Graph RAG（Neo4j），用于多跳推理

---

## 当前开发进度（2026-03-31）

- ✅ 开题报告、文献综述已完成
- ✅ 完整 Vibe Coding 方案已输出
- ✅ 项目骨架目录结构已设计完成
- ✅ docker-compose.yml、pom.xml、requirements.txt 已生成
- ⏳ **当前阶段：Phase 0 环境搭建（待完成）**
- ⏳ 尚未开始写任何功能代码

---

## 开发阶段规划

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 0 | Docker 环境搭建（Milvus + MySQL + Redis + Ollama） | **当前阶段** |
| Phase 1 | Python 异常检测服务（FastAPI + PyOD） | 待开始 |
| Phase 2 | RAG 知识库构建（基础向量 → 混合检索 → Reranker） | 待开始 |
| Phase 3 | Multi-Agent 构建（Orchestrator + 3 个子 Agent） | 待开始 |
| Phase 4 | Human-in-the-Loop 执行审批流程 | 待开始 |
| Phase 5 | Vue3 前端 + 系统联调 | 待开始 |
| Phase 6 | 论文撰写 + 性能评估 | 待开始 |

---

## 关键注意事项（踩坑记录）

1. **Spring AI 版本**：变化快，不要参考超过 6 个月的教程；用 `ChatClient`，不是 `AiClient`
2. **Milvus 向量维度**：Collection 创建后维度不可改，早期锁定 BGE-M3（1024 维），不要随意换 Embedding 模型
3. **Python-Java 通信**：PyOD 处理大数据时 REST 可能超时，Java 端要设合理超时和重试
4. **Prompt 管理**：从第一天起用 `@Value` 或专门的 Prompt 类管理，不要硬编码在 Service 里
5. **LLM 切换**：application.yml 配置两套（DeepSeek API + Ollama），用 Profile 切换，不要改代码

---

## 项目目录结构

```
毕设/
├── netdata-ai-backend/          # Java Spring Boot 后端
│   ├── src/main/java/com/netdata/ops/
│   │   ├── core/agent/          # 4 个 Agent 实现
│   │   ├── core/ai/             # LLM客户端、Embedding、Prompt管理
│   │   ├── core/rag/            # RAG核心：切分、混合检索、重排
│   │   ├── controller/          # REST API
│   │   ├── service/             # 业务逻辑
│   │   ├── websocket/           # 实时通信（告警、审批）
│   │   └── config/              # 各组件配置
│   └── pom.xml
│
├── anomaly-detection-service/   # Python FastAPI 异常检测服务
│   └── app/
│       ├── api/                 # 接口（detect、train、health）
│       ├── core/                # 模型封装（PyOD/PySAD）
│       └── netdata/             # NetData 数据适配
│
├── netdata-ai-frontend/         # Vue3 前端
│   └── src/
│       ├── views/               # Chat、AlertDashboard、KnowledgeBase、ExecutionApproval
│       └── components/
│
├── docker-compose.yml           # 一键启动所有基础服务
├── sql/init.sql                 # MySQL 表结构
└── docs/                        # 技术文档
```

---

## 我的学习目标（帮助 AI 理解我要什么）

我不只是要「能跑起来」，我要通过这个项目真正理解：
- RAG 为什么要混合检索，RRF 算法是什么
- Tool Calling 的原理，LLM 如何决定调用哪个函数
- ReAct 模式的循环过程（思考→行动→观察→再思考）
- Human-in-the-Loop 在 Agent 系统中怎么实现

**请在生成代码时加详细注释，解释「为什么这么写」，而不只是「写了什么」。**

---

*最后更新：2026-03-31*
