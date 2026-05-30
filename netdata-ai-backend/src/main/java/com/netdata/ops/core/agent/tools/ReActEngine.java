package com.netdata.ops.core.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.core.ai.LLMFallbackHandler;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 推理引擎
 *
 * <p>LLM 驱动的 ReAct（Reasoning + Acting）推理循环引擎。
 * 通过 Thought → Action → Observation 循环，让 LLM 动态选择工具
 * 并基于工具执行结果进行逐步推理，最终得出结论。
 *
 * <p>核心流程：
 * <ol>
 *   <li>构建包含可用工具列表的 System Prompt</li>
 *   <li>循环调用 LLM，解析输出中的 Thought/Action/ActionInput 或 Final Answer</li>
 *   <li>执行 LLM 选择的工具，将结果作为 Observation 追加到历史</li>
 *   <li>重复直到 LLM 输出 Final Answer 或达到最大迭代次数</li>
 * </ol>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component("toolsReActEngine")
@Slf4j
public class ReActEngine {

    private final ToolRegistry toolRegistry;
    private final LLMFallbackHandler llmHandler;
    private final ObjectMapper objectMapper;

    private static final int MAX_ITERATIONS = 20;

    // 正则模式
    private static final Pattern THOUGHT_PATTERN = Pattern.compile(
            "Thought:\\s*(.+?)(?=\\nAction:|\\nFinal Answer:)", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(.+)");
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile(
            "Action Input:\\s*(.+)", Pattern.DOTALL);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile(
            "Final Answer:\\s*(.+)", Pattern.DOTALL);

    public ReActEngine(ToolRegistry toolRegistry, LLMFallbackHandler llmHandler) {
        this.toolRegistry = toolRegistry;
        this.llmHandler = llmHandler;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 执行 ReAct 推理循环
     *
     * @param query         用户问题
     * @param systemContext 系统上下文（可选的额外指令）
     * @return ReActResult 包含推理步骤和最终结论
     */
    public ReActResult execute(String query, String systemContext) {
        log.info("[ReActEngine] 开始推理: query='{}'", query);

        List<ReActStep> steps = new ArrayList<>();
        String finalAnswer = null;
        boolean completed = false;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("[ReActEngine] 第 {} 轮迭代", i + 1);

            // 构建完整 prompt 并调用 LLM
            String fullPrompt = buildFullPrompt(query, systemContext, steps);
            String llmOutput;

            try {
                llmOutput = llmHandler.call(fullPrompt);
            } catch (Exception e) {
                log.error("[ReActEngine] LLM 调用失败: {}", e.getMessage());
                finalAnswer = "LLM 服务调用失败，无法完成推理: " + e.getMessage();
                break;
            }

            if (llmOutput == null || llmOutput.isBlank()) {
                log.warn("[ReActEngine] LLM 返回空结果");
                finalAnswer = "LLM 未返回有效结果，推理中断。";
                break;
            }

            log.debug("[ReActEngine] LLM 输出:\n{}", llmOutput);

            // 解析 LLM 输出
            ParsedAction parsed = parseLLMOutput(llmOutput, i + 1);

            // 如果是最终答案，结束循环
            if (parsed.isFinalAnswer) {
                finalAnswer = parsed.finalAnswer;
                completed = true;
                log.info("[ReActEngine] 获得最终答案，共 {} 轮迭代", i + 1);
                break;
            }

            // 执行工具调用
            ReActStep step = ReActStep.builder()
                    .iteration(i + 1)
                    .thought(parsed.thought)
                    .action(parsed.action)
                    .build();

            // 解析 Action Input
            Map<String, Object> actionInput = parseActionInput(parsed.actionInput);
            step.setActionInput(actionInput);

            // 调用工具
            String observation = executeTool(parsed.action, actionInput);
            step.setObservation(observation);

            steps.add(step);
            log.debug("[ReActEngine] 第 {} 步完成: action={}, observation={}",
                    i + 1, parsed.action, observation.substring(0, Math.min(100, observation.length())));
        }

        // 如果循环结束仍未得到 Final Answer
        if (finalAnswer == null) {
            finalAnswer = "推理达到最大迭代次数（" + MAX_ITERATIONS + "），基于已有信息总结如下：\n"
                    + summarizeSteps(steps);
            log.warn("[ReActEngine] 达到最大迭代次数，强制结束");
        }

        return ReActResult.builder()
                .finalAnswer(finalAnswer)
                .steps(steps)
                .iterationCount(steps.size())
                .completed(completed)
                .build();
    }

    /**
     * 构建工具描述 prompt
     */
    private String buildToolPrompt() {
        List<ToolRegistry.ToolDescription> tools = toolRegistry.getToolDescriptions();
        StringBuilder sb = new StringBuilder();

        for (ToolRegistry.ToolDescription tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                sb.append("  参数:\n");
                for (String param : tool.getParameters()) {
                    sb.append("    - ").append(param).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 构建包含历史步骤的完整 prompt
     */
    private String buildFullPrompt(String query, String systemContext, List<ReActStep> steps) {
        StringBuilder sb = new StringBuilder();

        // System 指令
        sb.append("你是一个智能运维助手，使用 ReAct 方法解决问题。\n\n");
        sb.append("⚠️ 严格禁止编造 Observation！每轮必须只输出一个 Action，然后等待真实的工具执行结果！\n\n");
        sb.append("重要约束：\n");
        sb.append("- 不要在没有等待真实 Observation 的情况下编造后续步骤\n");
        sb.append("- 每轮推理只能输出一个 Action（或 Final Answer）\n");
        sb.append("- Observation 只能由系统提供，你绝对不能自己生成\n\n");

        sb.append("正确格式示例：\n");
        sb.append("Thought: 我需要先查看系统状态\n");
        sb.append("Action: get_system_status\n");
        sb.append("Action Input: {\"host\": \"localhost\"}\n\n");

        sb.append("错误格式示例（不要这样做）：\n");
        sb.append("❌ 错误：自己编造 Observation\n");
        sb.append("Thought: 我需要先查看系统状态\n");
        sb.append("Action: get_system_status\n");
        sb.append("Action Input: {\"host\": \"localhost\"}\n");
        sb.append("Observation: 系统运行正常（这是编造的！）\n");
        sb.append("Thought: 接下来我需要...（继续编造后续步骤）\n\n");

        // 额外系统上下文
        if (systemContext != null && !systemContext.isBlank()) {
            sb.append("附加上下文:\n").append(systemContext).append("\n\n");
        }

        // 可用工具列表
        sb.append("可用工具:\n");
        sb.append(buildToolPrompt());
        sb.append("\n");

        // 格式要求
        sb.append("回答格式要求:\n");
        sb.append("每一步必须严格按以下格式输出:\n");
        sb.append("Thought: [你的思考过程]\n");
        sb.append("Action: [工具名称]\n");
        sb.append("Action Input: {\"param1\": \"value1\", \"param2\": \"value2\"}\n\n");
        sb.append("当你得出最终结论时，使用:\n");
        sb.append("Thought: [最终思考]\n");
        sb.append("Final Answer: [你的最终回答]\n\n");

        // 历史推理步骤
        if (!steps.isEmpty()) {
            sb.append("历史推理步骤:\n");
            for (ReActStep step : steps) {
                sb.append("Thought: ").append(step.getThought()).append("\n");
                sb.append("Action: ").append(step.getAction()).append("\n");
                sb.append("Action Input: ").append(formatActionInput(step.getActionInput())).append("\n");
                sb.append("Observation: ").append(step.getObservation()).append("\n\n");
            }
        }

        // 用户问题
        sb.append("用户问题: ").append(query).append("\n");

        return sb.toString();
    }

    /**
     * 解析 LLM 输出
     */
    private ParsedAction parseLLMOutput(String output, int iteration) {
        ParsedAction parsed = new ParsedAction();

        log.info("[ReActEngine] 开始解析 LLM 输出，第 {} 轮迭代", iteration);
        log.debug("[ReActEngine] 原始 LLM 输出:\n{}", output);

        // 统计 Action 出现的次数
        int actionCount = 0;
        int lastIndex = 0;
        while (true) {
            int idx = output.indexOf("Action:", lastIndex);
            if (idx == -1) break;
            actionCount++;
            lastIndex = idx + 1;
        }

        log.info("[ReActEngine] 检测到 {} 个 Action", actionCount);

        String processedOutput = output;

        // 如果有多个 Action，只保留到第一个 Action Input
        if (actionCount > 1) {
            log.warn("[ReActEngine] 检测到多个 Action，将截断到第一个 Action Input");
            
            // 找到第一个 Action 的位置
            int firstActionIdx = output.indexOf("Action:");
            // 找到第二个 Action 的位置
            int secondActionIdx = output.indexOf("Action:", firstActionIdx + 1);
            
            // 找到第一个 Action Input 的结束位置
            int actionInputEndIdx = secondActionIdx;
            
            // 或者找到 Observation 的位置（如果存在）
            int observationIdx = output.indexOf("Observation:", firstActionIdx);
            if (observationIdx != -1 && observationIdx < secondActionIdx) {
                actionInputEndIdx = observationIdx;
            }
            
            processedOutput = output.substring(0, actionInputEndIdx).trim();
            log.debug("[ReActEngine] 截断后的输出:\n{}", processedOutput);
        }

        // 检查 Final Answer 是否在第一个 Action 之前
        int finalAnswerIdx = processedOutput.indexOf("Final Answer:");
        int firstActionIdx = processedOutput.indexOf("Action:");
        
        if (finalAnswerIdx != -1 && firstActionIdx != -1 && finalAnswerIdx < firstActionIdx) {
            log.warn("[ReActEngine] 检测到 Final Answer 在第一个 Action 之前");
            
            // 如果是第一轮迭代，谨慎处理 - 优先执行工具而不是直接返回 Final Answer
            if (iteration == 1) {
                log.info("[ReActEngine] 第一轮迭代，优先执行工具，忽略 Final Answer");
                // 截断 Final Answer 之前的内容
                processedOutput = processedOutput.substring(finalAnswerIdx + "Final Answer:".length()).trim();
            }
        }

        // 检查是否包含 Final Answer
        Matcher finalMatcher = FINAL_ANSWER_PATTERN.matcher(processedOutput);
        if (finalMatcher.find()) {
            parsed.isFinalAnswer = true;
            parsed.finalAnswer = finalMatcher.group(1).trim();

            // 尝试提取 thought
            Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(processedOutput);
            if (thoughtMatcher.find()) {
                parsed.thought = thoughtMatcher.group(1).trim();
            }
            log.info("[ReActEngine] 解析到 Final Answer");
            return parsed;
        }

        // 提取 Thought
        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(processedOutput);
        if (thoughtMatcher.find()) {
            parsed.thought = thoughtMatcher.group(1).trim();
        } else {
            // 尝试更宽松的匹配
            int thoughtIdx = processedOutput.indexOf("Thought:");
            if (thoughtIdx >= 0) {
                int endIdx = processedOutput.indexOf("\n", thoughtIdx);
                if (endIdx > thoughtIdx) {
                    parsed.thought = processedOutput.substring(thoughtIdx + 8, endIdx).trim();
                }
            }
            if (parsed.thought == null) {
                parsed.thought = processedOutput.trim();
            }
        }

        // 提取 Action
        Matcher actionMatcher = ACTION_PATTERN.matcher(processedOutput);
        if (actionMatcher.find()) {
            parsed.action = actionMatcher.group(1).trim();
            log.info("[ReActEngine] 解析到 Action: {}", parsed.action);
        }

        // 提取 Action Input
        Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(processedOutput);
        if (inputMatcher.find()) {
            parsed.actionInput = inputMatcher.group(1).trim();
            log.debug("[ReActEngine] 解析到 Action Input: {}", parsed.actionInput);
        }

        // 如果无法解析出 Action，视为最终答案
        if (parsed.action == null || parsed.action.isBlank()) {
            parsed.isFinalAnswer = true;
            parsed.finalAnswer = processedOutput.trim();
            log.info("[ReActEngine] 未解析到 Action，视为 Final Answer");
        }

        log.info("[ReActEngine] LLM 输出解析完成，isFinalAnswer={}", parsed.isFinalAnswer);
        return parsed;
    }

    /**
     * 解析 Action Input JSON
     */
    private Map<String, Object> parseActionInput(String input) {
        if (input == null || input.isBlank()) {
            return new HashMap<>();
        }

        try {
            // 清理可能的多余字符（如行尾的换行、多余内容）
            String cleaned = input.trim();
            // 只取第一个 JSON 对象
            int braceStart = cleaned.indexOf('{');
            int braceEnd = cleaned.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                cleaned = cleaned.substring(braceStart, braceEnd + 1);
            }
            return objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[ReActEngine] Action Input JSON 解析失败，尝试简单解析: input={}", input);
            // 容错处理：尝试简单键值对解析
            return fallbackParseInput(input);
        }
    }

    /**
     * 容错解析 Action Input（当 JSON 解析失败时）
     */
    private Map<String, Object> fallbackParseInput(String input) {
        Map<String, Object> result = new HashMap<>();
        // 尝试提取引号中的键值对
        Pattern kvPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = kvPattern.matcher(input);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        if (result.isEmpty()) {
            result.put("raw_input", input);
        }
        return result;
    }

    /**
     * 执行工具调用
     */
    private String executeTool(String toolName, Map<String, Object> params) {
        if (toolName == null || toolName.isBlank()) {
            return "错误: 未指定工具名称";
        }

        String cleanName = cleanToolName(toolName).toLowerCase();
        Tool tool = toolRegistry.getTool(cleanName);

        if (tool == null) {
            log.warn("[ReActEngine] 工具不存在: {}", cleanName);
            return "错误: 工具 '" + cleanName + "' 不存在。可用工具: " + getAvailableToolNames();
        }

        try {
            long start = System.currentTimeMillis();
            String result = tool.execute(params);
            long duration = System.currentTimeMillis() - start;
            log.info("[ReActEngine] 工具 {} 执行完成，耗时 {}ms", cleanName, duration);
            return result;
        } catch (Exception e) {
            log.error("[ReActEngine] 工具 {} 执行异常: {}", cleanName, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 清理工具名称，去除 Markdown 格式符号和其他多余字符
     */
    private String cleanToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }

        String cleaned = toolName.trim();

        // 去除 Markdown 格式符号：*, `, #, >, -, 但保留下划线 _（因为工具名称中包含下划线）
        cleaned = cleaned.replaceAll("[\\*#>`\\-]+", "");

        // 去除前后的空白字符
        cleaned = cleaned.trim();

        return cleaned;
    }

    /**
     * 获取所有可用工具名称
     */
    private String getAvailableToolNames() {
        return toolRegistry.getToolDescriptions().stream()
                .map(ToolRegistry.ToolDescription::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("无可用工具");
    }

    /**
     * 格式化 Action Input 为字符串
     */
    private String formatActionInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return input.toString();
        }
    }

    /**
     * 基于已有步骤生成摘要
     */
    private String summarizeSteps(List<ReActStep> steps) {
        if (steps.isEmpty()) {
            return "未收集到足够信息进行分析。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("基于 ").append(steps.size()).append(" 步推理收集到的信息:\n");
        for (ReActStep step : steps) {
            sb.append("- ").append(step.getThought()).append("\n");
            sb.append("  观察: ").append(step.getObservation(), 0,
                    Math.min(200, step.getObservation().length())).append("\n");
        }
        return sb.toString();
    }

    // ========== 内部类 ==========

    /**
     * ReAct 执行结果
     */
    @Data
    @Builder
    public static class ReActResult {
        private String finalAnswer;
        private List<ReActStep> steps;
        private int iterationCount;
        private boolean completed;
    }

    /**
     * ReAct 单步记录
     */
    @Data
    @Builder
    public static class ReActStep {
        private int iteration;
        private String thought;
        private String action;
        private Map<String, Object> actionInput;
        private String observation;
    }

    /**
     * LLM 输出解析结果
     */
    @Data
    private static class ParsedAction {
        private boolean isFinalAnswer;
        private String finalAnswer;
        private String thought;
        private String action;
        private String actionInput;
    }
}
