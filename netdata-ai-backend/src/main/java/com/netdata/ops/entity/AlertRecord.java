package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警记录实体
 */
@Data
@TableName("alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String alertId;

    private String source;

    /**
     * 严重级别: info, warning, critical
     */
    private String severity;

    private String alertName;

    private String message;

    private String host;

    private String metricName;

    private String metricValue;

    private String threshold;

    /**
     * 状态: firing, resolved
     */
    private String status;

    private String diagnosisResult;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
