package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String email;

    private String phone;

    private String avatar;

    /**
     * 旧角色字段（兼容保留，新系统使用user_role关联表）
     */
    private String role;

    /**
     * 状态：0禁用 1启用
     */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    private Integer loginFailCount;

    private LocalDateTime lockedUntil;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime passwordChangedAt;

    private LocalDateTime passwordExpireAt;

    @TableField("is_first_login")
    private Integer isFirstLogin;
}
