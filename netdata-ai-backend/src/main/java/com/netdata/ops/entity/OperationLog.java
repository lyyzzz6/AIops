package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志实体
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private Long userId;

    private String username;

    private String module;

    private String action;

    private String target;

    private String description;

    private String requestMethod;

    private String requestUrl;

    private String requestParams;

    private Integer responseCode;

    private String ipAddress;

    private String userAgent;

    private Long executionTimeMs;

    /**
     * 操作结果: 0失败 1成功
     */
    private Integer status;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
