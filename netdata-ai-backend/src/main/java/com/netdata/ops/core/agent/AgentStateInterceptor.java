package com.netdata.ops.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================
 * Agent 状态拦截器
 * ============================================================
 *
 * 设计目的：
 * 实现 AgentInterceptor 接口，在 Agent 执行的各阶段自动管理执行状态：
 * - preExecute:  保存状态为 RUNNING
 * - postExecute: 保存状态为 COMPLETED，记录耗时
 * - onError:     保存状态为 FAILED，记录错误信息
 *
 * 自动注入到 Spring 容器，可通过 BaseAgent 增强构造函数传入拦截器链。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class AgentStateInterceptor implements AgentInterceptor {

    private final AgentStateManager stateManager;

    public AgentStateInterceptor(AgentStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * 前置处理：标记执行状态为 RUNNING
     *
     * @param context 执行上下文
     */
    @Override
    public void preExecute(AgentContext context) {
        String traceId = context.getTraceId();
        if (traceId == null) {
            log.debug("traceId 为空，跳过状态记录");
            return;
        }

        AgentExecutionState state = AgentExecutionState.builder()
                .traceId(traceId)
                .agentName(resolveAgentName(context))
                .status(AgentStateManager.ExecutionStatus.RUNNING)
                .query(context.getQuery())
                .userId(context.getUserId())
                .startTime(Instant.now())
                .metadata(buildMetadata(context))
                .build();

        stateManager.saveState(traceId, state);
        log.debug("Agent 执行状态 → RUNNING: traceId={}", traceId);
    }

    /**
     * 后置处理：标记执行状态为 COMPLETED，记录耗时
     *
     * @param context 执行上下文
     * @param result  执行结果
     */
    @Override
    public void postExecute(AgentContext context, AgentResult result) {
        String traceId = context.getTraceId();
        if (traceId == null) {
            return;
        }

        Instant now = Instant.now();
        AgentExecutionState existingState = stateManager.getState(traceId);
        Instant startTime = existingState != null ? existingState.getStartTime() : context.getStartTime();
        Long durationMs = startTime != null ? Duration.between(startTime, now).toMillis() : null;

        AgentExecutionState state = AgentExecutionState.builder()
                .traceId(traceId)
                .agentName(resolveAgentName(context))
                .status(AgentStateManager.ExecutionStatus.COMPLETED)
                .query(context.getQuery())
                .userId(context.getUserId())
                .startTime(startTime)
                .endTime(now)
                .durationMs(durationMs)
                .metadata(buildMetadata(context))
                .build();

        stateManager.saveState(traceId, state);
        log.debug("Agent 执行状态 → COMPLETED: traceId={}, duration={}ms", traceId, durationMs);
    }

    /**
     * 异常处理：标记执行状态为 FAILED，记录错误信息
     *
     * @param context 执行上下文
     * @param e       异常对象
     */
    @Override
    public void onError(AgentContext context, Exception e) {
        String traceId = context.getTraceId();
        if (traceId == null) {
            return;
        }

        Instant now = Instant.now();
        AgentExecutionState existingState = stateManager.getState(traceId);
        Instant startTime = existingState != null ? existingState.getStartTime() : context.getStartTime();
        Long durationMs = startTime != null ? Duration.between(startTime, now).toMillis() : null;

        // 区分超时异常和普通异常
        AgentStateManager.ExecutionStatus status = (e instanceof AgentTimeoutException)
                ? AgentStateManager.ExecutionStatus.TIMEOUT
                : AgentStateManager.ExecutionStatus.FAILED;

        AgentExecutionState state = AgentExecutionState.builder()
                .traceId(traceId)
                .agentName(resolveAgentName(context))
                .status(status)
                .query(context.getQuery())
                .userId(context.getUserId())
                .startTime(startTime)
                .endTime(now)
                .durationMs(durationMs)
                .errorMessage(e.getMessage())
                .metadata(buildMetadata(context))
                .build();

        stateManager.saveState(traceId, state);
        log.warn("Agent 执行状态 → {}: traceId={}, error={}", status, traceId, e.getMessage());
    }

    /**
     * 从上下文中解析 Agent 名称
     */
    private String resolveAgentName(AgentContext context) {
        if (context.getMetadata() != null && context.getMetadata().containsKey("agentName")) {
            return String.valueOf(context.getMetadata().get("agentName"));
        }
        // 根据意图类型推断
        if (context.getIntentType() != null) {
            return context.getIntentType().name().toLowerCase();
        }
        return "unknown";
    }

    /**
     * 构建元数据
     */
    private Map<String, Object> buildMetadata(AgentContext context) {
        Map<String, Object> metadata = new HashMap<>();
        if (context.getSessionId() != null) {
            metadata.put("sessionId", context.getSessionId());
        }
        if (context.getPriority() != 0) {
            metadata.put("priority", context.getPriority());
        }
        if (context.getRetryCount() > 0) {
            metadata.put("retryCount", context.getRetryCount());
        }
        return metadata;
    }
}
