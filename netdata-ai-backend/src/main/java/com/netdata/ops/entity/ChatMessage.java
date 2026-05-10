package com.netdata.ops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息实体
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /**
     * 角色：user/assistant/system
     */
    private String role;

    private String content;

    private Integer tokens;

    /**
     * 引用来源（JSON 字符串）
     */
    private String sources;

    /**
     * 元数据（JSON 字符串，包含 intent、agentUsed、executionTimeMs 等）
     */
    private String metadata;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
