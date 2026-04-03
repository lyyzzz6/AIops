# 毕设项目长期记忆

## 项目基本信息
- **题目**：面向NetData监控数据的智能运维问答与执行系统
- **学生**：刘一舟，2022软件工程03班，学号202203150713
- **导师**：陈波
- **答辩时间**：2026年6月

## 技术栈（已确认）
- 后端框架：Spring Boot 3.3.x + Spring AI 1.0.x（Java）
- 异常检测：Python FastAPI + PyOD + PySAD
- 向量数据库：Milvus 2.4
- LLM：DeepSeek-V3 API（主）+ Ollama 本地（备/开发调试）
- Embedding：BGE-M3 本地 或 DeepSeek Embedding API
- 知识图谱：Neo4j（可选进阶）
- 前端：Vue 3 + Element Plus
- 缓存：Redis 7.x
- 数据库：MySQL 8.0
- 容器编排：Docker Compose

## Agent 架构（已确认）
- 模式：Orchestrator + 三个子Agent（Orchestrator-Subagent 模式）
- Query Agent：问答，走 RAG 流程
- Analysis Agent：故障诊断，ReAct 模式
- Execution Agent：命令执行，Human-in-the-Loop 安全机制
- 框架：Spring AI（备选 LangChain4j）

## RAG 方案（已确认）
- 主方案：混合检索 RAG（向量检索 Milvus + BM25 关键词检索，RRF 融合）
- 加 Reranker（bge-reranker-v2-m3）提升效果
- 切分策略：按语义切分（Semantic Chunking）优于固定长度
- 进阶：Graph RAG（Neo4j），作为论文亮点

## 用户学习状态
- 了解 RAG 概念但未动手实践
- 核心目标：通过毕设学习 Agent 开发，打造简历亮点
- Vibe Coding 方案已输出（2026-03-25）

## 开发进度节点
- 2026.03.25：Vibe Coding 完整方案已输出，待开始 Phase 0 环境搭建
- 待完成：开题报告/文献综述 Word 格式转换并提交导师

## 关键注意事项
- Spring AI 版本变化快，避免使用超过6个月的教程（AiClient 已废弃，用 ChatClient）
- Milvus Collection 创建后向量维度不可改，早期固定 Embedding 模型
- Python-Java 通信注意超时和重试
- Prompt 从第一天用 @Value 或专门 Prompt 类管理，不硬编码
