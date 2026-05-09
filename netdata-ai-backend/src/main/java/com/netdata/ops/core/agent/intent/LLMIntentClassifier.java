package com.netdata.ops.core.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.core.agent.AgentContext;
import com.netdata.ops.core.agent.BaseAgent;
import com.netdata.ops.core.ai.LLMFallbackHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LLM 的语义意图分类器
 *
 * <p>通过调用大语言模型对用户查询进行深层语义理解，
 * 解决规则分类器难以处理的模糊意图和复合请求。
 *
 * <p>特点：
 * <ul>
 *   <li>利用对话历史进行上下文感知的意图识别</li>
 *   <li>JSON 结构化输出，便于自动化解析</li>
 *   <li>通过 LLMFallbackHandler 实现主/备模型容错</li>
 *   <li>解析失败时降级为 KNOWLEDGE_QUERY + 低置信度</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class LLMIntentClassifier implements IntentClassifier {

    private final LLMFallbackHandler llmHandler;
    private final ObjectMapper objectMapper;

    /**
     * 意图分类 Prompt 模板
     */
    private static final String CLASSIFY_PROMPT = """
            你是一个智能运维系统的意图识别模块。请分析用户的输入，判断其意图类型。
            
            可用的意图类型：
            1. KNOWLEDGE_QUERY - 知识问答（用户想了解某个概念、原理、操作方法）
            2. FAULT_DIAGNOSIS - 故障诊断（用户报告了问题，需要分析原因）
            3. COMMAND_EXECUTE - 命令执行（用户想要执行某个运维操作）
            4. HYBRID - 混合意图（包含多种意图的复合请求）
            
            用户输入：%s
            
            最近对话历史：
            %s
            
            请严格以纯JSON格式返回，不要包含任何其他内容：
            {"intent":"意图类型","confidence":置信度,"reasoning":"判断理由","suggested_tools":["工具1","工具2"]}
            
            注意：
            - intent必须是KNOWLEDGE_QUERY、FAULT_DIAGNOSIS、COMMAND_EXECUTE或HYBRID中的一个
            - confidence必须是0.0到1.0之间的数字
            - suggested_tools是字符串数组，可以为空数组[]
            - 只返回JSON，不要有markdown格式、解释说明或其他文字
            """;

    public LLMIntentClassifier(LLMFallbackHandler llmHandler, ObjectMapper objectMapper) {
        this.llmHandler = llmHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntentResult classify(String query, List<AgentContext.ChatMessage> history) {
        log.debug("[LLM分类] 开始语义分析: {}", query);

        try {
            // 1. 构建 Prompt
            String historyText = formatHistory(history);
            String prompt = String.format(CLASSIFY_PROMPT, query, historyText);

            // 2. 调用 LLM（带容错）
            String response = llmHandler.call(prompt);
            log.debug("[LLM分类] 原始响应: {}", response);

            // 3. 解析 JSON 响应
            return parseResponse(response);

        } catch (Exception e) {
            log.warn("[LLM分类] 分类失败，降级为默认结果。原因: {}", e.getMessage());
            return buildFallbackResult(query);
        }
    }

    /**
     * 格式化对话历史
     */
    private String formatHistory(List<AgentContext.ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }

        // 最多取最近 5 条
        List<AgentContext.ChatMessage> recent = history.size() > 5
                ? history.subList(history.size() - 5, history.size())
                : history;

        return recent.stream()
                .map(msg -> String.format("[%s]: %s", msg.getRole(), msg.getContent()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     *
     * @param response LLM 原始响应
     * @return 意图分类结果
     */
    private IntentResult parseResponse(String response) {
        try {
            // 提取 JSON 部分（处理可能的 markdown 包裹）
            String jsonStr = extractJson(response);
            JsonNode root = objectMapper.readTree(jsonStr);

            // 解析意图类型
            String intentStr = root.path("intent").asText("KNOWLEDGE_QUERY");
            BaseAgent.IntentType intentType = parseIntentType(intentStr);

            // 解析置信度
            double confidence = root.path("confidence").asDouble(0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence)); // 限制范围

            // 解析推理过程
            String reasoning = root.path("reasoning").asText("");

            // 解析建议工具
            List<String> suggestedTools = new ArrayList<>();
            JsonNode toolsNode = root.path("suggested_tools");
            if (toolsNode.isArray()) {
                for (JsonNode tool : toolsNode) {
                    suggestedTools.add(tool.asText());
                }
            }

            return IntentResult.builder()
                    .intentType(intentType)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .suggestedTools(suggestedTools)
                    .classifierSource("llm")
                    .fromCache(false)
                    .build();

        } catch (Exception e) {
            log.warn("[LLM分类] JSON 解析失败: {}，原始响应: {}", e.getMessage(), response);
            throw new RuntimeException("LLM 响应解析失败", e);
        }
    }

    /**
     * 从响应中提取 JSON 字符串
     * 处理 LLM 可能返回 ```json ... ``` 包裹的情况
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new RuntimeException("LLM 返回空响应");
        }

        String trimmed = response.trim();

        // 处理 markdown 代码块包裹
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }

        // 尝试找到 JSON 对象的起始位置
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }

    /**
     * 解析意图类型字符串为枚举
     */
    private BaseAgent.IntentType parseIntentType(String intentStr) {
        try {
            return BaseAgent.IntentType.valueOf(intentStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("[LLM分类] 未知意图类型: {}，默认使用 KNOWLEDGE_QUERY", intentStr);
            return BaseAgent.IntentType.KNOWLEDGE_QUERY;
        }
    }

    /**
     * 构建降级结果（LLM 调用/解析失败时使用）
     */
    private IntentResult buildFallbackResult(String query) {
        return IntentResult.builder()
                .intentType(BaseAgent.IntentType.KNOWLEDGE_QUERY)
                .confidence(0.4)
                .reasoning("LLM 分类失败，降级为默认知识问答")
                .classifierSource("llm")
                .fromCache(false)
                .build();
    }
}
