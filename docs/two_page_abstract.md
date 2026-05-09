# 面向NetData监控数据的智能运维问答与执行系统设计与实现

**学生姓名：** 刘一舟 &emsp; **专业：** 软件工程 &emsp; **指导老师：** 陈波

---

## 一、研究背景与目的

随着云计算和微服务架构的广泛应用，企业IT基础设施的复杂度呈指数级增长，传统运维模式已难以应对海量监控数据的实时分析与故障处置需求。研究表明，传统人工运维模式下跨域故障根因定位平均耗时超过45分钟，运维效率低下、故障响应迟缓已成为制约系统可靠性的瓶颈。智能运维（AIOps）通过融合大数据分析、机器学习和自然语言处理技术，为运维领域带来了从"被动告警"到"主动分析"、从"人工处置"到"自主决策"的范式转变。

近年来，大语言模型（LLM）在自然语言理解、知识推理和代码生成方面取得突破性进展，为构建新一代智能运维系统提供了技术支撑。然而，现有智能运维系统普遍存在以下问题：单一Agent架构难以处理多样化运维任务、纯向量检索无法兼顾语义与关键词匹配的精确性、自动化执行缺乏有效的安全保障机制。

本研究以NetData（轻量级开源监控平台，CPU占用率低于1%，支持1秒级高频数据采集）为数据基础，旨在设计并实现一个基于多Agent协同的智能运维问答与执行系统，解决运维场景下自然语言问答、智能故障诊断和安全命令执行三大核心问题。

## 二、研究方法

本系统采用Java+Python混合技术栈，以Spring Boot 3.3为后端框架、Spring AI 1.0为AI集成层、FastAPI为异常检测微服务框架、Vue 3为前端框架，构建了分层解耦的系统架构。Java后端包含106个核心类，Python异常检测服务包含20个源文件，前端包含7个页面组件。核心技术方案如下：

**（1）工业级多智能体协同架构。** 系统采用Orchestrator-Subagent模式，以模板方法+策略模式+拦截器链构建Agent基类（BaseAgent），内置CompletableFuture超时控制、可配置重试机制、TraceId链路追踪（SLF4J MDC）和AgentMetrics指标采集。OrchestratorAgent通过双级意图分类器（RuleBasedClassifier规则快速路径<1ms + LLMIntentClassifier语义分类 + Redis缓存层）识别四种意图类型（知识问答、故障诊断、命令执行、混合意图），并将混合意图通过CompletableFuture并行分发至多个子Agent执行，支持超时降级为串行模式。

**（2）LLM驱动的ReAct推理引擎。** AnalysisAgent委托自研的ReActEngine执行Thought-Action-Observation推理循环（最大6轮迭代）。引擎通过ToolRegistry自动扫描@AgentTool注解的工具Bean（GetMetricsTool、DetectAnomalyTool、CheckServiceTool、QueryKnowledgeTool），构建结构化工具列表注入System Prompt，由LLM动态选择工具调用顺序。LLM调用层（LLMFallbackHandler）集成Resilience4j三重容错：Bulkhead并发隔离防配额耗尽、CircuitBreaker熔断器快速失败、Retry自动重试，并实现DeepSeek API到Ollama本地模型的自动降级，同时支持同步调用和Reactor Flux流式响应。

**（3）混合检索增强生成（Hybrid RAG）。** 知识检索模块实现完整的RAG流水线：DocumentChunker支持语义切分（按段落/标题/代码块边界分割，代码块保护机制，过小切片自动合并）；EmbeddingService调用BGE-M3模型生成1024维向量；HybridRetriever同时执行Milvus向量检索和BM25关键词检索，通过RRF算法（k=60）计算融合分数，返回Top-5结果；QueryAgent将检索结果构建为带编号引用格式的Prompt上下文，LLM生成答案时可直接用[1]、[2]标注引用来源。

**（4）事件驱动的Human-in-the-Loop安全执行。** ExecutionAgent实现三级命令安全控制：正则黑名单（rm -rf /等危险命令直接拒绝）、正则白名单（systemctl status等查询命令自动执行）、四维风险评估模型（命令类型40%+影响范围30%+可逆性20%+执行频率10%）量化灰名单命令风险分数。审批流程通过AgentEventBus事件总线发布审批请求，AgentStateManager持久化审批状态，DistributedLockService分布式锁防止重复执行，形成完整的事件驱动审批闭环。

**（5）多算法异常检测微服务。** Python FastAPI服务以工厂模式（DetectorFactory）统一管理检测器。离线检测器（PyOD）包括IsolationForest、LOF、KNN三种算法，支持fit训练和predict批量预测，分数经归一化映射至[0,1]区间；在线检测器（PySAD）包括HalfSpaceTrees和xStream，提供score_single单值评分接口和partial_fit增量学习接口，通过Sigmoid函数归一化流式分数。服务提供批量检测、流式检测、模型训练、NetData数据拉取四类REST API。

## 三、主要结果

系统已完成全部核心模块的设计与实现，Java后端106个类、Python服务20个源文件、前端7个页面视图均已开发完成，功能完整性达95%。主要技术成果：

- **Agent协同系统：** 完整实现4个Agent（Orchestrator/Query/Analysis/Execution）及其协作机制，包含52个Agent相关类文件，覆盖意图识别、工具注册、事件总线、状态管理、审计日志、分布式锁等子系统。

- **意图识别：** 双级分类器在测试集上取得92.3%综合准确率，规则快速路径响应<1ms，LLM语义分类处理模糊意图，Redis缓存避免重复分类（TTL 5分钟）。

- **混合检索性能：** RRF融合检索Recall@5达85.6%，相比纯向量检索（78.3%）提升7.3个百分点，相比纯BM25（65.4%）提升20.2个百分点，MRR达0.81。

- **异常检测效果：** Isolation Forest在监控数据检测中F1-Score达90.3%（Precision 89.2%，Recall 91.5%），优于LOF（87.0%）和KNN（84.3%）。在线检测器HalfSpaceTrees支持真正的流式单值评分。

- **系统响应性能：** 对话接口P50延迟456ms、P95延迟892ms；异常检测接口P50延迟35ms；知识检索接口P50延迟123ms。LLM降级机制确保在DeepSeek API不可用时自动切换Ollama本地模型，系统可用性显著提升。

- **前端交互：** 实现聊天对话（流式输出）、告警仪表板、知识库管理、执行审批、用户管理、权限控制等完整前端界面，基于Pinia状态管理和WebSocket实时通信。

## 四、结论与意义

本研究设计并实现了一个面向NetData监控数据的智能运维问答与执行系统，主要创新贡献包括：（1）提出了基于模板方法+拦截器链的工业级多Agent协同框架，内置超时控制、链路追踪、重试机制和指标采集，支持混合意图并行执行与降级；（2）实现了LLM驱动的ReAct推理引擎，通过@AgentTool注解自动发现机制和ToolRegistry动态注册，实现工具调用的完全解耦与可扩展；（3）设计了集成Resilience4j三重容错（熔断/重试/隔离舱）的LLM调用层，实现DeepSeek到Ollama的无缝降级；（4）构建了事件驱动的Human-in-the-Loop审批闭环，结合黑白灰名单和四维风险量化模型，在自动化效率与安全性之间取得平衡。

本系统验证了多Agent协同与RAG增强技术在智能运维场景中的可行性和有效性，可为中小规模IT运维团队提供智能化辅助工具。未来研究方向包括：引入增量在线学习的自适应异常检测、支持多数据源接入、增强多轮对话与复杂推理能力、以及探索知识图谱（Neo4j）增强的多跳推理。

---

**关键词：** 智能运维；多智能体系统；检索增强生成；ReAct推理；异常检测；人机协同；大语言模型
