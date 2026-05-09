package com.netdata.ops.core.agent;

import com.netdata.ops.core.agent.tools.ReActEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Analysis Agent - ReAct 故障诊断（动态化版本）
 * ============================================================
 *
 * 职责：
 * 1. 委托 ReActEngine 执行 LLM 驱动的推理循环
 * 2. LLM 动态决策工具选择（替代硬编码流程）
 * 3. 将 ReActResult 转换为 AgentResult（含诊断报告和命令建议）
 *
 * 架构变更（v2）：
 * - 移除内部类 ReActStep、ToolCall
 * - 移除 decideAction()、executeTool() 等硬编码方法
 * - 注入 ReActEngine，由 LLM 动态决定工具调用顺序
 * - 保留 WebClient 供后续直接 API 调用使用
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
public class AnalysisAgent extends BaseAgent {

    private final WebClient webClient;
    private final ReActEngine reActEngine;

    public AnalysisAgent(WebClient.Builder webClientBuilder, ReActEngine reActEngine,
                         AgentMetrics agentMetrics, List<AgentInterceptor> interceptors) {
        super("AnalysisAgent", AgentType.ANALYSIS, agentMetrics, interceptors);
        this.webClient = webClientBuilder
                .baseUrl("http://localhost:8001")
                .build();
        this.reActEngine = reActEngine;
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        log.info("[AnalysisAgent] 开始 ReAct 诊断: {}", context.getQuery());

        // 构建系统上下文
        String systemContext = buildSystemContext(context);

        // 委托 ReActEngine 执行推理
        ReActEngine.ReActResult reActResult = reActEngine.execute(context.getQuery(), systemContext);

        // 转换为 AgentResult
        return convertToAgentResult(reActResult, context);
    }

    /**
     * 根据 AgentContext 构建额外系统提示
     */
    private String buildSystemContext(AgentContext context) {
        StringBuilder sb = new StringBuilder();

        // 意图信息
        if (context.getIntentType() != null) {
            sb.append("用户意图类型: ").append(context.getIntentType()).append("\n");
        }

        // 置信度
        if (context.getConfidence() != null) {
            sb.append("意图置信度: ").append(String.format("%.2f", context.getConfidence())).append("\n");
        }

        // 会话上下文
        if (context.getChatHistory() != null && !context.getChatHistory().isEmpty()) {
            sb.append("历史对话摘要:\n");
            int historySize = Math.min(3, context.getChatHistory().size());
            for (int i = context.getChatHistory().size() - historySize; i < context.getChatHistory().size(); i++) {
                AgentContext.ChatMessage msg = context.getChatHistory().get(i);
                sb.append("  [").append(msg.getRole()).append("]: ")
                        .append(msg.getContent(), 0, Math.min(100, msg.getContent().length()))
                        .append("\n");
            }
        }

        // 元数据
        if (context.getMetadata() != null && !context.getMetadata().isEmpty()) {
            sb.append("附加元数据: ").append(context.getMetadata()).append("\n");
        }

        // 角色定位
        sb.append("\n你的角色是故障诊断专家，请：\n");
        sb.append("1. 先获取相关指标数据\n");
        sb.append("2. 进行异常检测分析\n");
        sb.append("3. 查询知识库获取历史案例\n");
        sb.append("4. 检查相关服务状态\n");
        sb.append("5. 综合分析得出根因和建议\n");

        return sb.toString();
    }

    /**
     * 将 ReActResult 转换为 AgentResult
     */
    private AgentResult convertToAgentResult(ReActEngine.ReActResult reActResult, AgentContext context) {
        // 构建诊断报告
        AgentResult.DiagnosisReport report = buildDiagnosisReport(reActResult);

        // 构建命令建议
        List<AgentResult.CommandSuggestion> commands = buildCommandSuggestions(reActResult);

        // 构建工具调用历史
        List<AgentResult.ToolCallRecord> toolHistory = reActResult.getSteps().stream()
                .map(step -> AgentResult.ToolCallRecord.builder()
                        .toolName(step.getAction())
                        .params(step.getActionInput())
                        .result(step.getObservation())
                        .success(step.getObservation() != null && !step.getObservation().startsWith("错误"))
                        .build())
                .collect(Collectors.toList());

        return AgentResult.builder()
                .success(true)
                .response(reActResult.getFinalAnswer())
                .intentType(IntentType.FAULT_DIAGNOSIS)
                .diagnosisReport(report)
                .suggestedCommands(commands)
                .toolCallHistory(toolHistory)
                .build();
    }

    /**
     * 从 ReAct 结果构建诊断报告
     */
    private AgentResult.DiagnosisReport buildDiagnosisReport(ReActEngine.ReActResult reActResult) {
        // 收集证据
        List<String> evidence = reActResult.getSteps().stream()
                .map(ReActEngine.ReActStep::getObservation)
                .filter(Objects::nonNull)
                .filter(obs -> !obs.startsWith("错误"))
                .collect(Collectors.toList());

        // 从 Final Answer 中提取摘要和根因
        String finalAnswer = reActResult.getFinalAnswer();
        String summary = extractSection(finalAnswer, "摘要", "诊断结果", finalAnswer);
        String rootCause = extractSection(finalAnswer, "根因", "原因", "由 LLM 推理得出，详见完整回答");

        // 提取建议
        List<String> recommendations = extractRecommendations(finalAnswer);

        return AgentResult.DiagnosisReport.builder()
                .summary(summary.length() > 200 ? summary.substring(0, 200) + "..." : summary)
                .rootCause(rootCause.length() > 300 ? rootCause.substring(0, 300) + "..." : rootCause)
                .evidence(evidence)
                .recommendations(recommendations)
                .build();
    }

    /**
     * 构建命令建议（从 ReAct 最终结果中提取）
     */
    private List<AgentResult.CommandSuggestion> buildCommandSuggestions(ReActEngine.ReActResult reActResult) {
        // 默认建议（可被 LLM 结果覆盖）
        return List.of(
                AgentResult.CommandSuggestion.builder()
                        .command("top -n 1 | head -20")
                        .description("查看 CPU 占用最高的进程")
                        .riskLevel("LOW")
                        .requiresApproval(false)
                        .build(),
                AgentResult.CommandSuggestion.builder()
                        .command("ps aux --sort=-%cpu | head -10")
                        .description("按 CPU 排序显示进程")
                        .riskLevel("LOW")
                        .requiresApproval(false)
                        .build(),
                AgentResult.CommandSuggestion.builder()
                        .command("journalctl -u <service> --since '1 hour ago'")
                        .description("查看服务最近日志")
                        .riskLevel("LOW")
                        .requiresApproval(false)
                        .build()
        );
    }

    /**
     * 从文本中提取指定段落
     */
    private String extractSection(String text, String... keywords) {
        if (text == null) return "分析中...";

        for (String keyword : keywords) {
            int idx = text.indexOf(keyword);
            if (idx >= 0) {
                int endIdx = text.indexOf("\n", idx);
                if (endIdx > idx) {
                    String section = text.substring(idx, endIdx).trim();
                    // 去掉关键词前缀（如 "摘要："）
                    int colonIdx = section.indexOf("：");
                    if (colonIdx < 0) colonIdx = section.indexOf(":");
                    if (colonIdx >= 0 && colonIdx < 10) {
                        return section.substring(colonIdx + 1).trim();
                    }
                    return section;
                }
            }
        }

        // 如果都没找到，返回前 200 字符作为摘要
        return text.substring(0, Math.min(200, text.length()));
    }

    /**
     * 从最终回答中提取建议列表
     */
    private List<String> extractRecommendations(String text) {
        if (text == null) return List.of("请查看完整诊断结果");

        // 尝试提取带编号或符号的建议
        List<String> recommendations = new java.util.ArrayList<>();
        String[] lines = text.split("\n");
        boolean inRecommendation = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("建议") || trimmed.contains("推荐") || trimmed.contains("措施")) {
                inRecommendation = true;
                continue;
            }
            if (inRecommendation && (trimmed.startsWith("-") || trimmed.startsWith("•")
                    || trimmed.matches("^\\d+[.、].*"))) {
                String cleaned = trimmed.replaceFirst("^[-•\\d.、]+\\s*", "");
                if (!cleaned.isBlank()) {
                    recommendations.add(cleaned);
                }
            }
            if (inRecommendation && trimmed.isBlank()) {
                inRecommendation = false;
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("请根据诊断结果采取相应措施");
            recommendations.add("持续监控相关指标变化");
        }

        return recommendations;
    }

    /**
     * 覆盖超时时间：ReAct 循环需要更长时间
     */
    @Override
    protected long getTimeoutMs() {
        return 120_000L; // 2 分钟
    }
}
