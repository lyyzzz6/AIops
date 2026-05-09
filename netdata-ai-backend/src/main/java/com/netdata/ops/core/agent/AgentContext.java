package com.netdata.ops.core.agent;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * Agent 上下文 - 执行请求的封装
 * ============================================================
 *
 * 设计目的：
 * 封装 Agent 执行所需的全部上下文信息，采用 Builder 模式便于灵活构造。
 * 新增链路追踪、超时控制、重试等字段，支撑工业级 Agent 基础设施。
 *
 * 向后兼容：所有新增字段均为可选，现有代码无需修改即可编译。
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Data
@Builder
public class AgentContext {
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 用户查询内容
     */
    private String query;
    
    /**
     * 识别的意图类型
     */
    private BaseAgent.IntentType intentType;
    
    /**
     * 意图置信度
     */
    private Double confidence;
    
    /**
     * 历史对话
     */
    private List<ChatMessage> chatHistory;
    
    /**
     * 元数据（扩展字段）
     */
    private Map<String, Object> metadata;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    // ==================== 新增字段 ====================

    /**
     * 链路追踪 ID
     * 为什么需要：分布式场景下串联一次请求的全部日志，便于问题定位和调用链分析
     */
    private String traceId;

    /**
     * 父链路 ID
     * 为什么需要：Orchestrator 调用子 Agent 时传递父 traceId，形成调用树结构
     */
    private String parentTraceId;

    /**
     * 执行截止时间
     * 为什么用 Instant 而非 Duration：截止时间是绝对时间点，
     * 子 Agent 可以直接用来计算剩余时间，避免层层传递 duration 的复杂性
     */
    private Instant deadline;

    /**
     * 当前重试次数
     * 为什么放在 Context 中：让 doExecute() 可以感知到当前是第几次重试，
     * 便于子类实现差异化重试策略（如降级查询）
     */
    private int retryCount;

    /**
     * 执行优先级（默认0，数值越大优先级越高）
     * 为什么需要：未来引入优先级队列时，高优先级任务可以抢占资源
     */
    private int priority;

    /**
     * 执行开始时间
     * 为什么需要：记录实际开始执行的时间点，用于计算排队耗时和执行耗时的拆分
     */
    private Instant startTime;

    /**
     * 聊天消息
     */
    @Data
    @Builder
    public static class ChatMessage {
        private String role;  // user, assistant, system
        private String content;
        private LocalDateTime timestamp;
    }
    
    /**
     * 创建上下文副本（用于并行执行场景，避免共享可变状态）
     */
    public AgentContext copy() {
        return AgentContext.builder()
                .query(this.query)
                .userId(this.userId)
                .sessionId(this.sessionId)
                .chatHistory(this.chatHistory != null ? new java.util.ArrayList<>(this.chatHistory) : null)
                .metadata(this.metadata != null ? new java.util.HashMap<>(this.metadata) : null)
                .intentType(this.intentType)
                .confidence(this.confidence)
                .traceId(this.traceId)
                .parentTraceId(this.parentTraceId)
                .deadline(this.deadline)
                .retryCount(this.retryCount)
                .priority(this.priority)
                .startTime(this.startTime)
                .build();
    }

    /**
     * 创建默认上下文
     */
    public static AgentContext of(String query) {
        return AgentContext.builder()
            .sessionId(java.util.UUID.randomUUID().toString())
            .query(query)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
