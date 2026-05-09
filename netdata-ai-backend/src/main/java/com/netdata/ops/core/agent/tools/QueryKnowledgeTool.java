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
 * <p>当 RAGService 不可用时，降级为模拟知识库数据返回。
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

        try {
            // 调用 RAG 服务进行混合检索
            List<HybridRetriever.RetrievalResult> results = ragService.retrieve(query, 5);

            if (results == null || results.isEmpty()) {
                return String.format("知识库检索: 未找到与「%s」相关的信息。建议更换关键词重试。", query);
            }

            // 格式化检索结果
            return formatRetrievalResults(query, results);
        } catch (Exception e) {
            log.error("[QueryKnowledgeTool] 知识检索失败: {}", e.getMessage(), e);
            // 降级：返回模拟知识库结果
            return fallbackSearch(query);
        }
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

    /**
     * 降级检索：当 RAG 服务不可用时，返回模拟知识库数据
     */
    private String fallbackSearch(String query) {
        log.warn("[QueryKnowledgeTool] RAG 服务不可用，使用降级模式");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("知识库检索结果 [关键词: %s, 降级模式]\n\n", query));

        String lowerQuery = query.toLowerCase();
        if (lowerQuery.contains("cpu") || lowerQuery.contains("负载")) {
            sb.append("--- 结果 1 (故障案例) ---\n");
            sb.append("标题: CPU 使用率飙升故障排查\n");
            sb.append("现象: CPU 使用率持续超过 90%\n");
            sb.append("根因: Java 应用死循环/GC 线程频繁执行\n");
            sb.append("解决: jstack 定位热点线程，修复无限循环\n\n");
        } else if (lowerQuery.contains("内存") || lowerQuery.contains("memory") || lowerQuery.contains("oom")) {
            sb.append("--- 结果 1 (故障案例) ---\n");
            sb.append("标题: 内存泄漏导致 OOM 故障\n");
            sb.append("现象: 应用内存持续增长，触发 OOM Killer\n");
            sb.append("根因: 缓存未设置过期策略\n");
            sb.append("解决: 引入 LRU 缓存策略，设置过期时间\n\n");
        } else if (lowerQuery.contains("磁盘") || lowerQuery.contains("disk")) {
            sb.append("--- 结果 1 (故障案例) ---\n");
            sb.append("标题: 磁盘空间不足导致服务宕机\n");
            sb.append("现象: 服务不可用，日志写入失败\n");
            sb.append("根因: 日志文件未配置轮转\n");
            sb.append("解决: 配置 logrotate 自动轮转\n\n");
        } else {
            sb.append("--- 结果 1 (通用建议) ---\n");
            sb.append("建议检查相关服务状态、日志和监控指标，进一步缩小问题范围。\n\n");
        }

        return sb.toString();
    }
}
