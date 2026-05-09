package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统权限实体
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 权限编码，格式: module:action
     * 例如: knowledge:write, execution:approve
     */
    private String permissionCode;

    private String permissionName;

    /**
     * 所属模块: user, knowledge, alert, execution, chat, system, approval
     */
    private String module;

    /**
     * 操作类型: read, write, delete, approve, execute, role_assign等
     */
    private String action;

    private String description;

    /**
     * 风险等级: low, medium, high
     */
    private String riskLevel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
