package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限审批请求实体
 */
@Data
@TableName("permission_request")
public class PermissionRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestNo;

    private Long requesterId;

    /**
     * 请求类型: ROLE_ASSIGN, PERMISSION_GRANT, TEMP_ELEVATION
     */
    private String requestType;

    private Long targetUserId;

    private Long targetRoleId;

    /**
     * 目标权限ID列表（JSON数组格式）
     */
    private String targetPermissionIds;

    private String reason;

    /**
     * 临时授权持续时长（小时）
     */
    private Integer durationHours;

    /**
     * 系统评估风险等级: low, medium, high
     */
    private String riskLevel;

    /**
     * 状态: PENDING, REVIEWING, APPROVED, REJECTED, EXPIRED
     */
    private String status;

    private Long currentApproverId;

    private Long approvedBy;

    private String rejectReason;

    private LocalDateTime approvedAt;

    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
