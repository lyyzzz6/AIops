package com.netdata.ops.core.agent;

/**
 * ============================================================
 * Agent 超时异常
 * ============================================================
 *
 * 设计目的：
 * 专用异常类型，区分超时与其他运行时异常。
 * 携带 agentName 和 timeoutMs 元信息，方便上层进行针对性处理
 * （如告警、降级、重试策略调整等）。
 *
 * 为什么不用通用 TimeoutException：
 * 1. TimeoutException 是 checked exception，不适合模板方法模式
 * 2. 需要携带 Agent 维度信息用于指标和日志
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
public class AgentTimeoutException extends RuntimeException {

    private final String agentName;
    private final long timeoutMs;

    public AgentTimeoutException(String agentName, long timeoutMs) {
        super(String.format("Agent [%s] 执行超时，超时阈值: %dms", agentName, timeoutMs));
        this.agentName = agentName;
        this.timeoutMs = timeoutMs;
    }

    public AgentTimeoutException(String agentName, long timeoutMs, Throwable cause) {
        super(String.format("Agent [%s] 执行超时，超时阈值: %dms", agentName, timeoutMs), cause);
        this.agentName = agentName;
        this.timeoutMs = timeoutMs;
    }

    public String getAgentName() {
        return agentName;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
