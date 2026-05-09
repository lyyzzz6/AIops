package com.netdata.ops.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================
 * Agent 审计日志记录器
 * ============================================================
 *
 * 设计目的：
 * 每次 Agent 执行时记录结构化审计日志，用于合规审计、问题追溯和运营分析。
 * 采用异步写入策略（@Async），确保审计记录不阻塞 Agent 主流程。
 *
 * 写入双通道：
 * 1. SLF4J 结构化 JSON 日志 - 便于 ELK/Loki 等日志平台检索
 * 2. MySQL 持久化 - 支撑审计查询和统计报表
 *
 * 线程安全：无状态设计 + Spring 单例，天然线程安全
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class AgentAuditLogger {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /**
     * SQL: 插入审计记录到 agent_audit_log 表
     * 表结构由 Flyway/手动 DDL 管理，此处只负责写入
     */
    private static final String INSERT_SQL =
            "INSERT INTO agent_audit_log (trace_id, user_id, agent_name, intent_type, query, " +
                    "success, duration_ms, tool_calls, error_message, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public AgentAuditLogger(DataSource dataSource) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 异步记录审计日志
     *
     * 为什么用 @Async：
     * 审计日志写入涉及数据库 IO，如果同步执行会增加 Agent 响应时间。
     * 异步执行后，主线程可以立即返回结果给用户，审计写入在后台完成。
     *
     * 容错策略：即使数据库写入失败，SLF4J 日志仍然会记录，不会丢失审计信息。
     *
     * @param record 审计记录
     */
    @Async
    public void logExecution(AgentAuditRecord record) {
        // 通道1：结构化 JSON 日志输出到 SLF4J
        writeToLog(record);

        // 通道2：持久化到数据库
        writeToDB(record);
    }

    /**
     * 写入结构化 JSON 日志
     * 格式：[AUDIT] {json}，便于日志平台通过前缀过滤审计日志
     */
    private void writeToLog(AgentAuditRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            log.info("[AUDIT] {}", json);
        } catch (Exception e) {
            log.warn("[AUDIT] 序列化审计记录失败 | traceId={} | error={}",
                    record.getTraceId(), e.getMessage());
        }
    }

    /**
     * 持久化审计记录到 MySQL
     * 为什么不用 MyBatis-Plus：审计模块应尽量减少外部依赖，
     * 原生 JDBC 更轻量且不依赖 Mapper 扫描路径配置
     */
    private void writeToDB(AgentAuditRecord record) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            ps.setString(1, record.getTraceId());
            ps.setString(2, record.getUserId());
            ps.setString(3, record.getAgentName());
            ps.setString(4, record.getIntentType());
            ps.setString(5, truncate(record.getQuery(), 2000));
            ps.setBoolean(6, record.isSuccess());
            ps.setLong(7, record.getDurationMs());
            ps.setString(8, record.getToolCalls() != null ?
                    String.join(",", record.getToolCalls()) : null);
            ps.setString(9, truncate(record.getErrorMessage(), 1000));
            ps.setObject(10, record.getTimestamp());

            ps.executeUpdate();
        } catch (Exception e) {
            // 数据库写入失败不应影响业务，仅打印警告
            log.warn("[AUDIT] 数据库写入失败 | traceId={} | error={}",
                    record.getTraceId(), e.getMessage());
        }
    }

    /**
     * 字符串截断工具方法，防止超长内容写入数据库时报错
     */
    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    // ==================== 审计记录数据模型 ====================

    /**
     * Agent 审计记录
     *
     * 字段设计说明：
     * - traceId：关联分布式链路，支持跨服务追踪
     * - userId：记录操作人，满足审计合规要求
     * - toolCalls：记录工具调用链，用于分析 Agent 行为模式
     * - durationMs：性能分析基础数据
     */
    @Data
    @Builder
    public static class AgentAuditRecord {
        /** 链路追踪 ID */
        private String traceId;
        /** 用户 ID */
        private String userId;
        /** Agent 名称 */
        private String agentName;
        /** 意图类型 */
        private String intentType;
        /** 用户查询内容 */
        private String query;
        /** 执行是否成功 */
        private boolean success;
        /** 执行耗时（毫秒） */
        private long durationMs;
        /** 调用的工具列表 */
        private List<String> toolCalls;
        /** 错误信息（仅失败时有值） */
        private String errorMessage;
        /** 记录时间戳 */
        private LocalDateTime timestamp;
    }
}
