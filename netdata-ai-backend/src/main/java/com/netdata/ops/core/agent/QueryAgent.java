package com.netdata.ops.core.agent;

import com.netdata.ops.core.ai.LLMFallbackHandler;
import com.netdata.ops.core.rag.HybridRetriever;
import com.netdata.ops.core.rag.RAGService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Query Agent - RAG + LLM 知识问答
 * ============================================================
 *
 * 职责：
 * 1. 接收用户问题
 * 2. 通过 RAG 混合检索相关知识片段
 * 3. 构建带编号引用的 Prompt 上下文
 * 4. 调用 LLM（DeepSeek → Ollama 降级）生成结构化答案
 * 5. 附带来源引用列表返回
 *
 * 设计理由：
 * - 使用 LLMFallbackHandler 而非直接调 ChatClient：获得自动降级、熔断、重试能力
 * - Prompt 模板外部化到 QueryAgentPromptTemplate：便于调优和 A/B 测试
 * - 检索结果为空时仍调用 LLM：利用模型通用知识给出参考建议，但明确标注无佐证
 * - 保持 BaseAgent 继承体系：复用超时控制、链路追踪、拦截器等基础设施
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Component
public class QueryAgent extends BaseAgent {

    private final RAGService ragService;
    private final LLMFallbackHandler llmHandler;
    private final QueryAgentPromptTemplate promptTemplate;

    public QueryAgent(RAGService ragService,
                      LLMFallbackHandler llmHandler,
                      QueryAgentPromptTemplate promptTemplate,
                      AgentMetrics agentMetrics,
                      List<AgentInterceptor> interceptors) {
        super("QueryAgent", AgentType.QUERY, agentMetrics, interceptors);
        this.ragService = ragService;
        this.llmHandler = llmHandler;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 执行知识问答流程
     *
     * 完整流程：
     * 1. RAG 混合检索（向量 + BM25 + RRF 融合）
     * 2. 根据检索结果选择 Prompt 策略（有结果/无结果）
     * 3. 调用 LLM 生成答案（内置 DeepSeek → Ollama 降级）
     * 4. 组装来源引用列表
     * 5. 返回结构化 AgentResult
     */
    @Override
    protected AgentResult doExecute(AgentContext context) {
        String query = context.getQuery();
        log.info("[QueryAgent] 处理知识查询: {}", query);

        // ======== 第1步：RAG 混合检索 ========
        // 使用 HybridRetriever 执行向量检索 + BM25 检索 + RRF 融合排序
        List<HybridRetriever.RetrievalResult> results = ragService.retrieve(query, 5);
        log.info("[QueryAgent] 检索到 {} 条相关知识片段", results.size());

        // ======== 第2步：构建 Prompt 并调用 LLM ========
        String response;
        if (results.isEmpty()) {
            // 无检索结果：使用兜底提示词，让 LLM 基于通用知识回答但标明无佐证
            log.info("[QueryAgent] 未检索到相关资料，使用无结果提示词");
            String noResultPrompt = promptTemplate.getNoResultPrompt(query);
            response = callLLMSafely(noResultPrompt);
        } else {
            // 有检索结果：构建带编号引用的上下文，注入完整 Prompt
            String numberedContext = buildNumberedContext(results);
            String fullPrompt = promptTemplate.buildFullPrompt(numberedContext, query);
            log.debug("[QueryAgent] Prompt 总长度: {} 字符", fullPrompt.length());
            response = callLLMSafely(fullPrompt);
        }

        // ======== 第3步：构建来源引用列表 ========
        List<AgentResult.SourceCitation> citations = buildCitations(results);

        // ======== 第4步：组装返回结果 ========
        return AgentResult.builder()
                .success(true)
                .response(response)
                .intentType(IntentType.KNOWLEDGE_QUERY)
                .sources(citations)
                .confidence(results.isEmpty() ? 0.5 : 1.0)  // 无参考资料时降低置信度
                .cacheHit(false)
                .build();
    }

    /**
     * 安全调用 LLM（包含异常兜底）
     *
     * 为什么额外包一层 try-catch：
     * - LLMFallbackHandler 内部已有降级逻辑，但极端情况（如 Ollama 也挂了）会返回兜底文本
     * - 这里再加一层保护，确保 QueryAgent 不会因 LLM 调用异常而整体失败
     * - 符合"宁可降级也不能报错"的运维系统设计原则
     *
     * @param prompt 完整提示词
     * @return LLM 生成的响应文本
     */
    private String callLLMSafely(String prompt) {
        try {
            long startTime = System.currentTimeMillis();
            String response = llmHandler.call(prompt);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[QueryAgent] LLM 生成完成，耗时 {}ms，响应长度 {} 字符",
                    elapsed, response != null ? response.length() : 0);
            return response;
        } catch (Exception e) {
            // 所有 LLM 路径都失败的极端情况
            log.error("[QueryAgent] LLM 调用异常: {}", e.getMessage(), e);
            return "抱歉，AI 服务暂时不可用，请稍后重试。如需紧急帮助，请联系运维人员。";
        }
    }

    /**
     * 构建带编号的检索上下文
     *
     * 为什么用编号格式：
     * - LLM 在回答中可以直接用 [1]、[2] 引用对应资料
     * - 用户阅读时能快速定位引用来源
     * - 结构化格式有助于 LLM 理解多段资料的边界
     *
     * @param results 检索结果列表
     * @return 格式化的带编号上下文文本
     */
    private String buildNumberedContext(List<HybridRetriever.RetrievalResult> results) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {
            HybridRetriever.RetrievalResult result = results.get(i);
            context.append(String.format("### [%d] %s\n", i + 1, result.getTitle()));
            context.append(String.format("来源: %s | 相关度: %.3f\n", result.getSource(), result.getRrfScore()));
            context.append(result.getContent());
            context.append("\n\n");
        }

        return context.toString();
    }

    /**
     * 构建来源引用列表
     *
     * 为什么需要独立的引用列表：
     * - 前端可以在答案下方展示可点击的来源链接
     * - 用于审计追溯，确认 AI 答案的知识依据
     * - snippet 字段提供预览，用户无需打开原文即可判断相关性
     *
     * @param results 检索结果列表
     * @return 来源引用列表
     */
    private List<AgentResult.SourceCitation> buildCitations(
            List<HybridRetriever.RetrievalResult> results) {

        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        return results.stream()
                .map(r -> AgentResult.SourceCitation.builder()
                        .source(r.getSource())
                        .title(r.getTitle())
                        .score(r.getRrfScore())
                        .snippet(r.getContent().substring(0, Math.min(150, r.getContent().length())))
                        .build())
                .collect(Collectors.toList());
    }
}
