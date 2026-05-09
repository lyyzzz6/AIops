package com.netdata.ops.core.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ============================================================
 * 审批请求实体
 * ============================================================
 *
 * 设计目的：
 * 封装高风险命令的审批流程信息，支持审批状态流转：
 * PENDING → APPROVED / REJECTED → EXECUTED
 * 超时未审批自动过期（默认 30 分钟）。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 审批请求 ID
     */
    private String approvalId;

    /**
     * 关联的链路追踪 ID
     */
    private String traceId;

    /**
     * 发起用户 ID
     */
    private String userId;

    /**
     * 待审批的命令
     */
    private String command;

    /**
     * 风险等级（LOW / MEDIUM / HIGH / CRITICAL）
     */
    private String riskLevel;

    /**
     * 风险评分（0-100）
     */
    private int riskScore;

    /**
     * 操作描述
     */
    private String description;

    /**
     * 审批状态
     */
    private AgentStateManager.ApprovalStatus status;

    /**
     * 审批人
     */
    private String approver;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 最后更新时间
     */
    private Instant updatedAt;

    /**
     * 审批超时时间（默认 30 分钟后过期）
     */
    private Instant expiresAt;
}
