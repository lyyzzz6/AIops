package com.netdata.ops.core.agent;

import com.netdata.ops.core.agent.intent.IntentClassifier;
import com.netdata.ops.core.agent.intent.IntentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================
 * Orchestrator Agent - 意图识别与任务路由（双级分类增强版）
 * ============================================================
 *
 * 职责：
 * 1. 意图识别：通过双级分类器（规则快速路径 + LLM 语义分类）判断用户输入意图
 * 2. 任务路由：将任务分发给对应的子 Agent
 * 3. 结果汇总：整合子 Agent 的结果（混合意图并行执行）
 *
 * 双级分类架构：
 * - 第一级：RuleBasedClassifier（< 1ms，高置信度直接命中）
 * - 第二级：LLMIntentClassifier（语义分类，处理模糊/复合意图）
 * - 缓存层：Redis 缓存避免重复分类
 *
 * 意图类型：
 * - KNOWLEDGE_QUERY: 知识问答（如"什么是 CPU 飙升？"）
 * - FAULT_DIAGNOSIS: 故障诊断（如"为什么 CPU 飙升了？"）
 * - COMMAND_EXECUTE: 命令执行（如"重启 nginx 服务"）
 * - HYBRID: 混合意图（包含多种意图，并行调用多个子 Agent）
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Component
public class OrchestratorAgent extends BaseAgent {

    private final QueryAgent queryAgent;
    private final AnalysisAgent analysisAgent;
    private final ExecutionAgent executionAgent;

    /**
     * 混合意图分类器（通过 @Primary 自动选择 HybridIntentClassifier）
     */
    private final IntentClassifier intentClassifier;

    /**
     * 置信度阈值：低于此值时请求用户澄清
     */
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    /**
     * 混合意图并行执行超时时间（秒）
     */
    private static final long HYBRID_TIMEOUT_SECONDS = 25;

    /**
     * OrchestratorAgent 超时时间：180 秒（3分钟）
     * 覆盖 BaseAgent 默认的 60 秒，因为可能需要等待子 Agent 完成
     */
    @Override
    protected long getTimeoutMs() {
        return 180_000L;
    }

    public OrchestratorAgent(
            QueryAgent queryAgent,
            AnalysisAgent analysisAgent,
            ExecutionAgent executionAgent,
            IntentClassifier intentClassifier,
            AgentMetrics agentMetrics,
            List<AgentInterceptor> interceptors) {
        super("OrchestratorAgent", AgentType.ORCHESTRATOR, agentMetrics, interceptors);
        this.queryAgent = queryAgent;
        this.analysisAgent = analysisAgent;
        this.executionAgent = executionAgent;
        this.intentClassifier = intentClassifier;
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        log.info("Orchestrator 开始处理: {}", context.getQuery());

        // 1. 使用双级分类器进行意图识别
        IntentResult classification = intentClassifier.classify(
                context.getQuery(), context.getChatHistory());

        context.setIntentType(classification.getIntentType());
        context.setConfidence(classification.getConfidence());

        log.info("意图识别结果: type={}, confidence={}, source={}",
                classification.getIntentType(),
                classification.getConfidence(),
                classification.getClassifierSource());

        // 2. 低置信度时请求澄清
        if (classification.getConfidence() < CONFIDENCE_THRESHOLD) {
            return buildClarificationResponse(classification);
        }

        // 3. 路由到子 Agent（混合意图并行执行）
        return routeToSubAgent(context, classification);
    }

    /**
     * 路由到子 Agent
     *
     * @param context        执行上下文
     * @param classification 意图分类结果
     * @return Agent 执行结果
     */
    private AgentResult routeToSubAgent(AgentContext context, IntentResult classification) {
        return switch (classification.getIntentType()) {
            case KNOWLEDGE_QUERY -> queryAgent.execute(context);
            case FAULT_DIAGNOSIS -> analysisAgent.execute(context);
            case COMMAND_EXECUTE -> executionAgent.execute(context);
            case HYBRID -> executeHybrid(context);
        };
    }


    /**
     * 处理混合意图 - 使用 CompletableFuture 并行调用多个子 Agent
     *
     * <p>并行执行故障诊断和知识查询，合并结果返回给用户。
     * 通过 CompletableFuture 实现非阻塞并行，提升混合意图的响应速度。
     *
     * @param context 执行上下文
     * @return 合并后的 Agent 结果
     */
    private AgentResult executeHybrid(AgentContext context) {
        log.info("处理混合意图，启动并行子 Agent 执行");

        // 为并行执行创建独立上下文副本，避免共享可变状态竞态
        AgentContext diagnosisCtx = context.copy();
        AgentContext queryCtx = context.copy();

        // 并行调用故障诊断和知识查询
        CompletableFuture<AgentResult> diagnosisFuture =
                CompletableFuture.supplyAsync(() -> analysisAgent.execute(diagnosisCtx));
        CompletableFuture<AgentResult> queryFuture =
                CompletableFuture.supplyAsync(() -> queryAgent.execute(queryCtx));

        try {
            // 等待所有子任务完成（带超时控制）
            CompletableFuture.allOf(diagnosisFuture, queryFuture)
                    .get(HYBRID_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            AgentResult diagnosisResult = diagnosisFuture.get();
            AgentResult queryResult = queryFuture.get();

            // 合并结果
            return mergeResults(diagnosisResult, queryResult);

        } catch (Exception e) {
            log.warn("混合意图并行执行异常，降级为串行执行。原因: {}", e.getMessage());
            // 降级：串行执行故障诊断
            return executeHybridFallback(context);
        }
    }

    /**
     * 合并多个子 Agent 的执行结果
     */
    private AgentResult mergeResults(AgentResult diagnosisResult, AgentResult queryResult) {
        StringBuilder combinedResponse = new StringBuilder();

        // 合并故障诊断结果
        if (diagnosisResult.isSuccess() && diagnosisResult.getResponse() != null) {
            combinedResponse.append("## 故障诊断结果\n\n");
            combinedResponse.append(diagnosisResult.getResponse());
            combinedResponse.append("\n\n");
        }

        // 合并知识查询结果
        if (queryResult.isSuccess() && queryResult.getResponse() != null) {
            combinedResponse.append("## 相关知识\n\n");
            combinedResponse.append(queryResult.getResponse());
            combinedResponse.append("\n\n");
        }

        // 如果有建议命令，添加执行选项
        if (diagnosisResult.getSuggestedCommands() != null
                && !diagnosisResult.getSuggestedCommands().isEmpty()) {
            combinedResponse.append("## 建议执行的命令\n\n");
            for (AgentResult.CommandSuggestion cmd : diagnosisResult.getSuggestedCommands()) {
                combinedResponse.append("- `").append(cmd.getCommand()).append("`");
                if (cmd.isRequiresApproval()) {
                    combinedResponse.append(" (需要审批)");
                }
                combinedResponse.append("\n");
            }
        }

        return AgentResult.builder()
                .success(true)
                .response(combinedResponse.toString())
                .intentType(BaseAgent.IntentType.HYBRID)
                .suggestedCommands(diagnosisResult.getSuggestedCommands())
                .diagnosisReport(diagnosisResult.getDiagnosisReport())
                .build();
    }

    /**
     * 混合意图降级执行（并行失败时使用串行方式）
     */
    private AgentResult executeHybridFallback(AgentContext context) {
        log.info("混合意图降级为串行执行");

        StringBuilder combinedResponse = new StringBuilder();

        // 先进行故障诊断
        AgentResult diagnosisResult = analysisAgent.execute(context);
        if (diagnosisResult.isSuccess() && diagnosisResult.getResponse() != null) {
            combinedResponse.append("## 故障诊断结果\n\n");
            combinedResponse.append(diagnosisResult.getResponse());
            combinedResponse.append("\n\n");
        }

        // 如果有建议命令，添加执行选项
        if (diagnosisResult.getSuggestedCommands() != null
                && !diagnosisResult.getSuggestedCommands().isEmpty()) {
            combinedResponse.append("## 建议执行的命令\n\n");
            for (AgentResult.CommandSuggestion cmd : diagnosisResult.getSuggestedCommands()) {
                combinedResponse.append("- `").append(cmd.getCommand()).append("`");
                if (cmd.isRequiresApproval()) {
                    combinedResponse.append(" (需要审批)");
                }
                combinedResponse.append("\n");
            }
        }

        return AgentResult.builder()
                .success(true)
                .response(combinedResponse.toString())
                .intentType(BaseAgent.IntentType.HYBRID)
                .suggestedCommands(diagnosisResult.getSuggestedCommands())
                .diagnosisReport(diagnosisResult.getDiagnosisReport())
                .build();
    }

    /**
     * 构建澄清响应（低置信度时使用）
     *
     * @param classification 分类结果
     * @return 请求用户澄清的响应
     */
    private AgentResult buildClarificationResponse(IntentResult classification) {
        String message = String.format(
                "我不太确定您的意图（置信度: %.0f%%）。您是想：\n" +
                        "1. 查询运维知识？\n" +
                        "2. 诊断故障原因？\n" +
                        "3. 执行运维命令？\n\n" +
                        "请更详细地描述您的需求。\n\n" +
                        "（分类来源: %s，推理: %s）",
                classification.getConfidence() * 100,
                classification.getClassifierSource(),
                classification.getReasoning() != null ? classification.getReasoning() : "无"
        );

        return AgentResult.builder()
                .success(true)
                .response(message)
                .intentType(classification.getIntentType())
                .confidence(classification.getConfidence())
                .build();
    }
}
