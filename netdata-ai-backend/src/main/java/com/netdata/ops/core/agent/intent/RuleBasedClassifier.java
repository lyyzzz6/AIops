package com.netdata.ops.core.agent.intent;

import com.netdata.ops.core.agent.AgentContext;
import com.netdata.ops.core.agent.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 基于规则的意图分类器
 *
 * <p>使用正则表达式和关键词匹配进行快速意图识别。
 * 适用于高置信度的明确意图场景，作为 LLM 分类的快速前置路径。
 *
 * <p>置信度计算策略：
 * <ul>
 *   <li>单一意图匹配：匹配模式数 / 该意图总模式数（加权）</li>
 *   <li>多意图匹配：标记为 HYBRID，置信度取最高分</li>
 *   <li>无匹配：默认 KNOWLEDGE_QUERY，低置信度</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class RuleBasedClassifier implements IntentClassifier {

    /**
     * 意图识别规则模式（按意图类型分组）
     * 每个模式组包含正则表达式列表和对应的权重
     */
    private static final Map<BaseAgent.IntentType, List<WeightedPattern>> INTENT_PATTERNS;

    static {
        INTENT_PATTERNS = new LinkedHashMap<>();

        // 知识问答模式
        INTENT_PATTERNS.put(BaseAgent.IntentType.KNOWLEDGE_QUERY, List.of(
                new WeightedPattern(Pattern.compile(".*什么是.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*如何.*"), 0.8),
                new WeightedPattern(Pattern.compile(".*怎么.*"), 0.8),
                new WeightedPattern(Pattern.compile(".*介绍.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*说明.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*解释.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*区别.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*原理.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*含义.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*概念.*"), 1.0)
        ));

        // 故障诊断模式
        INTENT_PATTERNS.put(BaseAgent.IntentType.FAULT_DIAGNOSIS, List.of(
                new WeightedPattern(Pattern.compile(".*为什么.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*原因.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*诊断.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*分析.*"), 0.8),
                new WeightedPattern(Pattern.compile(".*排查.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*异常.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*飙升.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*故障.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*报警.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*告警.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*宕机.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*超时.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*不可用.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*挂了.*"), 1.0)
        ));

        // 命令执行模式
        INTENT_PATTERNS.put(BaseAgent.IntentType.COMMAND_EXECUTE, List.of(
                new WeightedPattern(Pattern.compile(".*重启.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*启动.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*停止.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*执行.*"), 0.9),
                new WeightedPattern(Pattern.compile(".*运行.*"), 0.8),
                new WeightedPattern(Pattern.compile(".*清理.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*删除.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*修改.*"), 0.8),
                new WeightedPattern(Pattern.compile(".*部署.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*扩容.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*回滚.*"), 1.0),
                new WeightedPattern(Pattern.compile(".*更新.*"), 0.8)
        ));
    }

    @Override
    public IntentResult classify(String query, List<AgentContext.ChatMessage> history) {
        log.debug("[规则分类] 开始分析查询: {}", query);

        // 计算每种意图的加权得分
        Map<BaseAgent.IntentType, Double> scores = new LinkedHashMap<>();
        Map<BaseAgent.IntentType, List<String>> matchedPatterns = new LinkedHashMap<>();

        for (Map.Entry<BaseAgent.IntentType, List<WeightedPattern>> entry : INTENT_PATTERNS.entrySet()) {
            double totalWeight = 0.0;
            double matchedWeight = 0.0;
            List<String> matched = new ArrayList<>();

            for (WeightedPattern wp : entry.getValue()) {
                totalWeight += wp.weight;
                if (wp.pattern.matcher(query).matches()) {
                    matchedWeight += wp.weight;
                    matched.add(wp.pattern.pattern());
                }
            }

            double score = totalWeight > 0 ? matchedWeight / totalWeight : 0.0;
            scores.put(entry.getKey(), score);
            matchedPatterns.put(entry.getKey(), matched);
        }

        // 找出得分最高的意图
        BaseAgent.IntentType bestIntent = BaseAgent.IntentType.KNOWLEDGE_QUERY;
        double maxScore = 0.0;
        int matchingIntents = 0;

        for (Map.Entry<BaseAgent.IntentType, Double> entry : scores.entrySet()) {
            if (entry.getValue() > 0) {
                matchingIntents++;
            }
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                bestIntent = entry.getKey();
            }
        }

        // 判断是否为混合意图：多个意图都有匹配且得分相近
        if (matchingIntents > 1) {
            // 检查是否有多个意图得分接近（差距 < 30%）
            double threshold = maxScore * 0.7;
            long closeScoreCount = scores.values().stream()
                    .filter(s -> s >= threshold && s > 0)
                    .count();

            if (closeScoreCount > 1) {
                bestIntent = BaseAgent.IntentType.HYBRID;
                // 混合意图置信度取最高分的 80%
                maxScore = maxScore * 0.8;
            }
        }

        // 无任何匹配时，默认知识问答，低置信度
        if (maxScore == 0.0) {
            maxScore = 0.3;
            bestIntent = BaseAgent.IntentType.KNOWLEDGE_QUERY;
        }

        // 构建推理描述
        String reasoning = buildReasoning(scores, matchedPatterns, bestIntent);

        log.debug("[规则分类] 结果: intent={}, confidence={}, matchingIntents={}",
                bestIntent, maxScore, matchingIntents);

        return IntentResult.builder()
                .intentType(bestIntent)
                .confidence(maxScore)
                .reasoning(reasoning)
                .classifierSource("rule")
                .fromCache(false)
                .build();
    }

    /**
     * 构建人类可读的推理描述
     */
    private String buildReasoning(Map<BaseAgent.IntentType, Double> scores,
                                  Map<BaseAgent.IntentType, List<String>> matchedPatterns,
                                  BaseAgent.IntentType bestIntent) {
        StringBuilder sb = new StringBuilder();
        sb.append("规则分类结果：").append(bestIntent).append("\n");
        sb.append("各意图得分：\n");
        for (Map.Entry<BaseAgent.IntentType, Double> entry : scores.entrySet()) {
            sb.append("  - ").append(entry.getKey()).append(": ")
                    .append(String.format("%.2f", entry.getValue()));
            List<String> matched = matchedPatterns.get(entry.getKey());
            if (matched != null && !matched.isEmpty()) {
                sb.append(" (匹配 ").append(matched.size()).append(" 条规则)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 带权重的匹配模式
     */
    private record WeightedPattern(Pattern pattern, double weight) {
    }
}
