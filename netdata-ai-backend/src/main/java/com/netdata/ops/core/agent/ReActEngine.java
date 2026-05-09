package com.netdata.ops.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.core.agent.tools.Tool;
import com.netdata.ops.core.agent.tools.ToolRegistry;
import com.netdata.ops.core.ai.LLMFallbackHandler;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct 推理引擎
 *
 * <p>实现 ReAct（Reasoning + Acting）推理模式，由 LLM 动态决策工具调用顺序。
 * 与硬编码的工具调用顺序不同，本引擎让 LLM 根据当前上下文自主选择下一步行动。
 *
 * <p>执行流程：
 * <ol>
 *   <li>构建 Prompt（包含可用工具描述 + 已有步骤 + 用户问题）</li>
 *   <li>调用 LLM 获取决策（选择工具 or 输出结论）</li>
 *   <li>如果 LLM 选择了工具，执行工具并记录 Observation</li>
 *   <li>循环直到 LLM 判断信息充分，输出最终结论</li>
 *   <li>超时或达到最大步数时，基于已有信息生成部分结果</li>
 * </ol>
 *
 * <p>设计理由：
 * <ul>
 *   <li>解耦推理引擎和具体 Agent，可被多个 Agent 复用</li>
 *   <li>通过 ToolRegistry 动态获取可用工具，新增工具无需修改引擎</li>
 *   <li>超时和最大步数双重保护，防止无限循环</li>
 *   <li>JSON 解析容错，处理 LLM 返回格式不规范的情况</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReActEngine {

    private final LLMFallbackHandler llmHandler;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /** 默认最大推理步数 */
    private static final int DEFAULT_MAX_STEPS = 5;

    /** 默认超时时间 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * ReAct Prompt 模板
     * 指导 LLM 以 ReAct 模式进行推理和决策
     */
    private static final String REACT_PROMPT = """
            你是一个智能运维故障诊断专家，使用 ReAct（推理+行动）模式进行问题分析。
            
            ## 可用工具
            %s
            
            ## 已完成的步骤
            %s
            
            ## 用户问题
            %s
            
            ## 指令
            请决定下一步行动。如果已经收集了足够信息可以得出结论，返回 finished=true。
            
            以 JSON 格式返回（不要包含 markdown 代码块标记）：
            {
                "thought": "你的思考过程",
                "finished": false,
                "tool_name": "要调用的工具名称",
                "parameters": {"param1": "value1"}
            }
            
            或者如果已经可以得出结论：
            {
                "thought": "最终总结思考",
                "finished": true,
                "conclusion": {
                    "summary": "问题摘要",
                    "root_cause": "根因分析",
                    "recommendations": ["建议1", "建议2"]
                }
            }
            
            仅返回 JSON，不要包含任何其他内容。
            """;

    // ============================================================
    // 公共方法
    // ============================================================

    /**
     * 使用默认参数执行 ReAct 推理
     *
     * @param query 用户问题
     * @return 推理结果
     */
    public ReActResult execute(String query) {
        return execute(query, DEFAULT_MAX_STEPS, DEFAULT_TIMEOUT);
    }

    /**
     * 执行 ReAct 推理循环
     *
     * <p>核心流程：Thought → Action → Observation → 循环直到结论
     *
     * @param query    用户问题
     * @param maxSteps 最大推理步数
     * @param timeout  超时时间
     * @return 推理结果
     */
    public ReActResult execute(String query, int maxSteps, Duration timeout) {
        log.info("[ReActEngine] 开始推理, query='{}', maxSteps={}, timeout={}s",
                query, maxSteps, timeout.getSeconds());

        List<ReActStep> steps = new ArrayList<>();
        Instant startTime = Instant.now();
        Instant deadline = startTime.plus(timeout);

        for (int i = 0; i < maxSteps && Instant.now().isBefore(deadline); i++) {
            log.debug("[ReActEngine] 第 {} 步推理", i + 1);

            // 1. 构建 Prompt（含工具描述 + 已有步骤 + 问题）
            String prompt = buildPrompt(query, steps);

            // 2. 调用 LLM 获取决策
            String llmResponse;
            try {
                llmResponse = llmHandler.call(prompt);
            } catch (Exception e) {
                log.error("[ReActEngine] LLM 调用失败: {}", e.getMessage());
                break;
            }

            // 3. 解析 LLM 决策
            ReActDecision decision = parseDecision(llmResponse);
            if (decision == null) {
                log.warn("[ReActEngine] 无法解析 LLM 响应，结束推理");
                break;
            }

            // 4. 如果 LLM 判断已完成，返回最终结果
            if (decision.isFinished()) {
                log.info("[ReActEngine] LLM 判断推理完成，共 {} 步", steps.size());
                return buildFinalResult(steps, decision, startTime);
            }

            // 5. 执行工具
            String observation = executeToolCall(decision);

            // 6. 记录步骤
            ReActStep step = ReActStep.builder()
                    .thought(decision.getThought())
                    .toolName(decision.getToolName())
                    .parameters(decision.getParameters())
                    .observation(observation)
                    .build();
            steps.add(step);

            log.debug("[ReActEngine] 第 {} 步完成: tool={}, observation={}...",
                    i + 1, decision.getToolName(),
                    observation.substring(0, Math.min(80, observation.length())));
        }

        // 超时或达到最大步数，生成部分结果
        log.warn("[ReActEngine] 推理未完整完成（超时或达到最大步数），生成部分结果");
        return buildPartialResult(steps, query, startTime);
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /**
     * 构建 ReAct Prompt
     */
    private String buildPrompt(String query, List<ReActStep> steps) {
        // 构建工具描述
        String toolDescriptions = toolRegistry.getToolDescriptions().stream()
                .map(td -> String.format("- **%s**: %s\n  参数: %s",
                        td.getName(), td.getDescription(),
                        String.join(", ", td.getParameters())))
                .collect(Collectors.joining("\n"));

        // 构建已完成步骤
        String stepsText;
        if (steps.isEmpty()) {
            stepsText = "（尚无已完成步骤）";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < steps.size(); i++) {
                ReActStep step = steps.get(i);
                sb.append(String.format("### 步骤 %d\n", i + 1));
                sb.append(String.format("思考: %s\n", step.getThought()));
                sb.append(String.format("行动: %s(%s)\n", step.getToolName(), step.getParameters()));
                sb.append(String.format("观察: %s\n\n", step.getObservation()));
            }
            stepsText = sb.toString();
        }

        return String.format(REACT_PROMPT, toolDescriptions, stepsText, query);
    }

    /**
     * 解析 LLM 返回的决策 JSON
     *
     * <p>容错处理：
     * <ul>
     *   <li>去除 markdown 代码块包裹（```json ... ```）</li>
     *   <li>处理首尾空白</li>
     *   <li>字段缺失时使用默认值</li>
     * </ul>
     */
    private ReActDecision parseDecision(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return null;
        }

        // 清理 LLM 响应（去除 markdown 代码块包裹）
        String cleaned = cleanLLMResponse(llmResponse);

        try {
            JsonNode root = objectMapper.readTree(cleaned);

            ReActDecision.ReActDecisionBuilder builder = ReActDecision.builder()
                    .thought(getTextOrDefault(root, "thought", ""))
                    .finished(root.path("finished").asBoolean(false));

            if (builder.build().isFinished() || root.has("conclusion")) {
                // 解析结论
                builder.finished(true);
                JsonNode conclusionNode = root.path("conclusion");
                if (!conclusionNode.isMissingNode()) {
                    List<String> recommendations = new ArrayList<>();
                    JsonNode recNode = conclusionNode.path("recommendations");
                    if (recNode.isArray()) {
                        recNode.forEach(n -> recommendations.add(n.asText()));
                    }

                    builder.conclusion(ReActConclusion.builder()
                            .summary(getTextOrDefault(conclusionNode, "summary", "分析完成"))
                            .rootCause(getTextOrDefault(conclusionNode, "root_cause", "需要进一步分析"))
                            .recommendations(recommendations)
                            .build());
                }
            } else {
                // 解析工具调用
                builder.toolName(getTextOrDefault(root, "tool_name", ""));
                JsonNode paramsNode = root.path("parameters");
                if (!paramsNode.isMissingNode() && paramsNode.isObject()) {
                    Map<String, Object> params = objectMapper.convertValue(paramsNode, Map.class);
                    builder.parameters(params);
                } else {
                    builder.parameters(new HashMap<>());
                }
            }

            return builder.build();
        } catch (JsonProcessingException e) {
            log.warn("[ReActEngine] JSON 解析失败: {}, 原始响应: {}",
                    e.getMessage(), cleaned.substring(0, Math.min(200, cleaned.length())));
            return null;
        }
    }

    /**
     * 清理 LLM 响应文本
     * 去除 markdown 代码块标记、首尾空白等
     */
    private String cleanLLMResponse(String response) {
        String cleaned = response.trim();

        // 去除 ```json ... ``` 包裹
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }

    /**
     * 安全获取 JSON 节点文本值
     */
    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode fieldNode = node.path(field);
        return fieldNode.isMissingNode() ? defaultValue : fieldNode.asText(defaultValue);
    }

    /**
     * 执行工具调用
     */
    private String executeToolCall(ReActDecision decision) {
        String toolName = decision.getToolName();
        Map<String, Object> params = decision.getParameters();

        if (toolName == null || toolName.isBlank()) {
            return "错误: LLM 未指定工具名称";
        }

        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("[ReActEngine] 工具不存在: {}", toolName);
            return String.format("工具不存在: %s。可用工具: %s",
                    toolName,
                    toolRegistry.getToolDescriptions().stream()
                            .map(ToolRegistry.ToolDescription::getName)
                            .collect(Collectors.joining(", ")));
        }

        try {
            return tool.execute(params != null ? params : new HashMap<>());
        } catch (Exception e) {
            log.error("[ReActEngine] 工具执行异常: tool={}, error={}", toolName, e.getMessage(), e);
            return String.format("工具 %s 执行失败: %s", toolName, e.getMessage());
        }
    }

    /**
     * 构建完整推理结果
     */
    private ReActResult buildFinalResult(List<ReActStep> steps, ReActDecision decision, Instant startTime) {
        ReActConclusion conclusion = decision.getConclusion();
        if (conclusion == null) {
            conclusion = ReActConclusion.builder()
                    .summary("分析完成")
                    .rootCause("LLM 未提供详细结论")
                    .recommendations(List.of("建议查看详细推理步骤"))
                    .build();
        }

        return ReActResult.builder()
                .complete(true)
                .steps(steps)
                .conclusion(conclusion)
                .totalSteps(steps.size())
                .totalDurationMs(Duration.between(startTime, Instant.now()).toMillis())
                .build();
    }

    /**
     * 构建部分推理结果（超时或达到最大步数时）
     */
    private ReActResult buildPartialResult(List<ReActStep> steps, String query, Instant startTime) {
        // 基于已有步骤的观察结果，生成部分结论
        String summary = steps.isEmpty() ?
                "推理未能完成，未获取到有效信息" :
                "推理部分完成，已收集 " + steps.size() + " 步观察数据";

        String evidence = steps.stream()
                .map(ReActStep::getObservation)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        ReActConclusion partialConclusion = ReActConclusion.builder()
                .summary(summary)
                .rootCause("推理未完整完成，根据已收集信息初步分析: " + query)
                .recommendations(List.of(
                        "建议增加推理步数或超时时间后重试",
                        "可手动查看已收集的观察数据进行分析"
                ))
                .build();

        return ReActResult.builder()
                .complete(false)
                .steps(steps)
                .conclusion(partialConclusion)
                .totalSteps(steps.size())
                .totalDurationMs(Duration.between(startTime, Instant.now()).toMillis())
                .build();
    }

    // ============================================================
    // 内部数据类
    // ============================================================

    /**
     * ReAct 推理步骤
     */
    @Data
    @Builder
    public static class ReActStep {
        /** 思考过程 */
        private String thought;
        /** 调用的工具名称 */
        private String toolName;
        /** 工具调用参数 */
        private Map<String, Object> parameters;
        /** 工具执行结果（Observation） */
        private String observation;
    }

    /**
     * LLM 返回的决策
     */
    @Data
    @Builder
    public static class ReActDecision {
        /** 思考过程 */
        private String thought;
        /** 是否已完成推理 */
        private boolean finished;
        /** 要调用的工具名称 */
        private String toolName;
        /** 工具调用参数 */
        private Map<String, Object> parameters;
        /** 最终结论（finished=true 时） */
        private ReActConclusion conclusion;
    }

    /**
     * ReAct 推理结论
     */
    @Data
    @Builder
    public static class ReActConclusion {
        /** 问题摘要 */
        private String summary;
        /** 根因分析 */
        private String rootCause;
        /** 建议措施 */
        private List<String> recommendations;
    }

    /**
     * ReAct 推理结果
     */
    @Data
    @Builder
    public static class ReActResult {
        /** 是否完整完成推理 */
        private boolean complete;
        /** 推理步骤列表 */
        private List<ReActStep> steps;
        /** 最终结论 */
        private ReActConclusion conclusion;
        /** 总步数 */
        private int totalSteps;
        /** 总耗时（毫秒） */
        private long totalDurationMs;
    }
}
