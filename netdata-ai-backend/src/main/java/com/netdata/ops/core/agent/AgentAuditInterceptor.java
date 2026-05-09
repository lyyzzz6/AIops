package com.netdata.ops.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Agent 审计拦截器
 * ============================================================
 *
 * 设计目的：
 * 实现 AgentInterceptor 接口，在 Agent 执行前后自动收集审计信息。
 * 通过 ThreadLocal 存储开始时间，在执行完成后计算耗时并构建审计记录。
 *
 * 工作流程：
 * 1. preExecute：记录执行开始时间到 ThreadLocal
 * 2. postExecute：计算耗时，构建成功审计记录，异步写入
 * 3. onError：计算耗时，构建失败审计记录，异步写入
 *
 * 为什么用 ThreadLocal：
 * 同一线程内的 pre/post 调用需要共享开始时间，ThreadLocal 避免了参数传递。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class AgentAuditInterceptor implements AgentInterceptor {

    private final AgentAuditLogger auditLogger;

    /**
     * ThreadLocal 存储执行开始时间
     * 为什么不用 Context.startTime：startTime 可能由 BaseAgent 设置，
     * 拦截器需要精确控制自己的计时起点
     */
    private final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();

    public AgentAuditInterceptor(AgentAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /**
     * 前置处理：记录开始时间
     */
    @Override
    public void preExecute(AgentContext context) {
        startTimeHolder.set(System.currentTimeMillis());
    }

    /**
     * 后置处理：构建成功审计记录并异步写入
     *
     * 从 AgentResult 中提取工具调用历史，便于审计追溯 Agent 的每一步行为
     */
    @Override
    public void postExecute(AgentContext context, AgentResult result) {
        try {
            long duration = calculateDuration();

            // 从 result 中提取工具调用名称列表
            List<String> toolCalls = null;
            if (result.getToolCallHistory() != null) {
                toolCalls = result.getToolCallHistory().stream()
                        .map(AgentResult.ToolCallRecord::getToolName)
                        .collect(Collectors.toList());
            }

            AgentAuditLogger.AgentAuditRecord record = AgentAuditLogger.AgentAuditRecord.builder()
                    .traceId(context.getTraceId())
                    .userId(context.getUserId())
                    .agentName(result.getAgentName())
                    .intentType(context.getIntentType() != null ?
                            context.getIntentType().name() : null)
                    .query(context.getQuery())
                    .success(result.isSuccess())
                    .durationMs(duration)
                    .toolCalls(toolCalls)
                    .errorMessage(result.getErrorMessage())
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogger.logExecution(record);
        } finally {
            // 清理 ThreadLocal，防止内存泄漏
            startTimeHolder.remove();
        }
    }

    /**
     * 异常处理：构建失败审计记录并异步写入
     */
    @Override
    public void onError(AgentContext context, Exception e) {
        try {
            long duration = calculateDuration();

            AgentAuditLogger.AgentAuditRecord record = AgentAuditLogger.AgentAuditRecord.builder()
                    .traceId(context.getTraceId())
                    .userId(context.getUserId())
                    .agentName("unknown")  // 异常时可能无法获取 Agent 名称
                    .intentType(context.getIntentType() != null ?
                            context.getIntentType().name() : null)
                    .query(context.getQuery())
                    .success(false)
                    .durationMs(duration)
                    .errorMessage(e.getMessage())
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogger.logExecution(record);
        } finally {
            startTimeHolder.remove();
        }
    }

    /**
     * 计算执行耗时
     * 如果 ThreadLocal 中没有开始时间（理论上不应发生），返回 -1 表示异常
     */
    private long calculateDuration() {
        Long startTime = startTimeHolder.get();
        if (startTime == null) {
            log.warn("[AuditInterceptor] 未找到开始时间，可能 preExecute 未被调用");
            return -1;
        }
        return System.currentTimeMillis() - startTime;
    }
}
