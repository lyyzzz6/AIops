package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户角色关联实体
 */
@Data
@TableName("user_role")
public class UserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    /**
     * 授权人ID
     */
    private Long grantedBy;

    private LocalDateTime grantedAt;

    /**
     * 过期时间（临时授权场景）
     */
    private LocalDateTime expiresAt;
}
