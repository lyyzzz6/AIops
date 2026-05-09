package com.netdata.ops.core.agent.event;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

/**
 * Agent 间通信消息
 *
 * 用于 Agent 间异步通信的消息体，包含源/目标 Agent、消息类型、载荷等信息。
 * 作为 AgentEvent 的 payload 在事件总线中传递。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Data
@Builder
public class AgentMessage {
    /** 消息 ID */
    private String messageId;
    /** 源 Agent 名称 */
    private String sourceAgent;
    /** 目标 Agent 名称（为空表示广播） */
    private String targetAgent;
    /** 消息类型 */
    private MessageType type;
    /** 链路追踪 ID */
    private String traceId;
    /** 消息负载 */
    private Map<String, Object> payload;
    /** 时间戳 */
    private Instant timestamp;
    /** 优先级（1-10，10最高） */
    private int priority;
    
    public enum MessageType {
        /** 任务请求 */
        TASK_REQUEST,
        /** 任务结果 */
        TASK_RESULT,
        /** 审批请求 */
        APPROVAL_REQUEST,
        /** 审批响应 */
        APPROVAL_RESPONSE,
        /** Agent 状态变更 */
        STATE_CHANGE,
        /** 心跳消息 */
        HEARTBEAT,
        /** 错误通知 */
        ERROR_NOTIFICATION
    }
}
