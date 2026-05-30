package com.netdata.ops.core.agent.tools;

import com.netdata.ops.core.rag.HybridRetriever;
import com.netdata.ops.core.rag.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具
 *
 * <p>从运维知识库中检索相关信息，支持故障案例、最佳实践、操作手册等知识的检索。
 * 底层调用 {@link RAGService} 实现混合检索（向量 + BM25）。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@RequiredArgsConstructor
@AgentTool(
        name = "query_knowledge",
        description = "从运维知识库中检索相关信息。支持故障案例、最佳实践、操作手册等知识的检索。",
        parameters = {"query: 检索关键词或问题描述"}
)
public class QueryKnowledgeTool implements Tool {

    private final RAGService ragService;

    @Override
    public String getName() {
        return "query_knowledge";
    }

    @Override
    public String getDescription() {
        return "从运维知识库中检索相关信息";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");

        if (query.isBlank()) {
            return "错误: 检索关键词不能为空，请提供 query 参数";
        }

        log.info("[QueryKnowledgeTool] 知识检索: query={}", query);

        // 强制使用真实向量库，不使用模拟数据
        List<HybridRetriever.RetrievalResult> results = ragService.retrieve(query, 5);

        if (results == null || results.isEmpty()) {
            return String.format("知识库检索: 未找到与「%s」相关的信息。建议更换关键词重试。", query);
        }

        // 格式化检索结果
        return formatRetrievalResults(query, results);
    }

    /**
     * 格式化 RAG 检索结果为可读文本
     */
    private String formatRetrievalResults(String query, List<HybridRetriever.RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("知识库检索结果 [关键词: %s, 命中: %d 条]\n\n", query, results.size()));

        for (int i = 0; i < results.size(); i++) {
            HybridRetriever.RetrievalResult result = results.get(i);
            Double score = result.getFinalScore() != null ? result.getFinalScore() : 0.0;
            sb.append(String.format("--- 结果 %d (相关度: %.4f) ---\n", i + 1, score));
            sb.append(String.format("来源: %s\n", result.getSource() != null ? result.getSource() : "未知"));
            sb.append(result.getContent());
            sb.append("\n\n");
        }

        return sb.toString();
    }
}
