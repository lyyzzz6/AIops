package com.netdata.ops.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ============================================================
 * Agent 日志拦截器
 * ============================================================
 *
 * 设计目的：
 * 实现 AgentInterceptor 接口，提供统一的结构化日志输出。
 * 与 AgentAuditInterceptor 的区别：
 * - 本拦截器关注实时可观测性（控制台/文件日志）
 * - AuditInterceptor 关注持久化审计（数据库记录）
 *
 * 日志格式设计：
 * 使用 [Agent={name}] 前缀 + 关键字段的 KV 格式，便于日志平台检索和告警规则配置。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class LoggingAgentInterceptor implements AgentInterceptor {

    /**
     * ThreadLocal 存储执行开始时间，用于计算耗时
     */
    private final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();

    /**
     * 前置处理：记录 Agent 开始执行日志
     *
     * 输出格式：[Agent={name}] 开始执行 | traceId={} | query={}
     * 为什么截断 query：防止超长查询污染日志可读性
     */
    @Override
    public void preExecute(AgentContext context) {
        startTimeHolder.set(System.currentTimeMillis());

        String intentName = context.getIntentType() != null ?
                context.getIntentType().name() : "UNKNOWN";

        log.info("[Agent={}] 开始执行 | traceId={} | intent={} | query={}",
                intentName,
                context.getTraceId(),
                intentName,
                truncateQuery(context.getQuery()));
    }

    /**
     * 后置处理：记录 Agent 执行完成日志
     *
     * 输出格式：[Agent={name}] 执行完成 | traceId={} | duration={}ms | success={}
     */
    @Override
    public void postExecute(AgentContext context, AgentResult result) {
        try {
            long duration = calculateDuration();

            log.info("[Agent={}] 执行完成 | traceId={} | duration={}ms | success={} | cacheHit={}",
                    result.getAgentName(),
                    context.getTraceId(),
                    duration,
                    result.isSuccess(),
                    result.isCacheHit());
        } finally {
            startTimeHolder.remove();
        }
    }

    /**
     * 异常处理：记录 Agent 执行异常日志
     *
     * 输出格式：[Agent={name}] 执行异常 | traceId={} | error={}
     * 日志级别使用 ERROR，便于告警系统捕获
     */
    @Override
    public void onError(AgentContext context, Exception e) {
        try {
            long duration = calculateDuration();

            String intentName = context.getIntentType() != null ?
                    context.getIntentType().name() : "UNKNOWN";

            log.error("[Agent={}] 执行异常 | traceId={} | duration={}ms | error={}",
                    intentName,
                    context.getTraceId(),
                    duration,
                    e.getMessage(),
                    e);  // 最后一个参数会输出完整堆栈
        } finally {
            startTimeHolder.remove();
        }
    }

    /**
     * 计算执行耗时
     */
    private long calculateDuration() {
        Long startTime = startTimeHolder.get();
        if (startTime == null) return -1;
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 截断查询内容，保留前 200 字符
     * 为什么是 200：足够展示查询意图，又不会过长影响日志可读性
     */
    private String truncateQuery(String query) {
        if (query == null) return "null";
        return query.length() > 200 ? query.substring(0, 200) + "..." : query;
    }
}
