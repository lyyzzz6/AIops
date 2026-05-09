package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 命令执行审计实体
 */
@Data
@TableName("execution_audit")
public class ExecutionAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long userId;

    private String command;

    private String commandType;

    private String targetHost;

    /**
     * 风险等级: low, medium, high, critical
     */
    private String riskLevel;

    private Integer riskScore;

    /**
     * 状态: pending, approved, rejected, executed, failed
     */
    private String status;

    private Long approverId;

    private String executionResult;

    private LocalDateTime approvedAt;

    private LocalDateTime executedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
