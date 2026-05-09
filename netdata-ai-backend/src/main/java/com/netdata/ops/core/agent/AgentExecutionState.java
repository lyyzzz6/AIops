package com.netdata.ops.core.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * ============================================================
 * Agent 执行状态实体
 * ============================================================
 *
 * 设计目的：
 * 记录单次 Agent 执行的完整生命周期状态信息，包括执行时间、状态流转、
 * 错误信息等，用于 Redis 持久化和状态查询。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 链路追踪 ID，唯一标识一次执行
     */
    private String traceId;

    /**
     * Agent 名称（如 analysis、execution、query）
     */
    private String agentName;

    /**
     * 执行状态
     */
    private AgentStateManager.ExecutionStatus status;

    /**
     * 用户查询内容
     */
    private String query;

    /**
     * 发起用户 ID
     */
    private String userId;

    /**
     * 执行开始时间
     */
    private Instant startTime;

    /**
     * 执行结束时间
     */
    private Instant endTime;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 错误信息（仅失败时有值）
     */
    private String errorMessage;

    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
}
