package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批流程记录实体
 */
@Data
@TableName("approval_flow")
public class ApprovalFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long requestId;

    private Integer stepOrder;

    private Long approverId;

    /**
     * 审批动作: APPROVE, REJECT, TRANSFER
     */
    private String action;

    private String comment;

    private LocalDateTime actedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
