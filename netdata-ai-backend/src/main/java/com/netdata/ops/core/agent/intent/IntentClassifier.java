package com.netdata.ops.core.agent.intent;

import com.netdata.ops.core.agent.AgentContext;

import java.util.List;

/**
 * 意图分类器接口
 *
 * <p>定义统一的意图分类契约，支持多种分类策略实现：
 * <ul>
 *   <li>RuleBasedClassifier：基于规则/关键词的快速分类</li>
 *   <li>LLMIntentClassifier：基于 LLM 的语义分类</li>
 *   <li>HybridIntentClassifier：组合策略（规则快速路径 + LLM 语义兜底）</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
public interface IntentClassifier {

    /**
     * 对用户查询进行意图分类
     *
     * @param query   用户查询文本
     * @param history 最近对话历史（可为 null 或空列表）
     * @return 意图分类结果
     */
    IntentResult classify(String query, List<AgentContext.ChatMessage> history);
}
