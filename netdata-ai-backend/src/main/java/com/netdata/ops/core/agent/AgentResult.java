package com.netdata.ops.core.agent;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * Agent 执行结果
 * ============================================================
 *
 * 设计目的：
 * 统一封装 Agent 执行的输出结果，包含业务结果和运行时元信息。
 * 新增工具调用历史、Token 消耗、缓存命中等字段，
 * 支撑可观测性和成本核算。
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Data
@Builder
public class AgentResult {
    
    /**
     * 执行是否成功
     */
    private boolean success;
    
    /**
     * Agent 名称
     */
    private String agentName;
    
    /**
     * Agent 类型
     */
    private BaseAgent.AgentType agentType;
    
    /**
     * 响应内容
     */
    private String response;
    
    /**
     * 识别的意图
     */
    private BaseAgent.IntentType intentType;
    
    /**
     * 置信度
     */
    private Double confidence;
    
    /**
     * 来源引用
     */
    private List<SourceCitation> sources;
    
    /**
     * 建议执行的命令
     */
    private List<CommandSuggestion> suggestedCommands;
    
    /**
     * 诊断报告
     */
    private DiagnosisReport diagnosisReport;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 执行耗时（毫秒）
     */
    private long executionTimeMs;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    // ==================== 新增字段 ====================

    /**
     * 关联的链路追踪 ID
     * 为什么需要：让调用方可以通过 traceId 关联本次结果的完整日志链路
     */
    private String traceId;

    /**
     * 工具调用历史
     * 为什么需要：ReAct 模式下记录每步工具调用的详情，便于调试和审计
     */
    private List<ToolCallRecord> toolCallHistory;

    /**
     * LLM Token 消耗统计
     * 为什么需要：Token 是 LLM 调用的核心成本指标，用于成本核算和预算控制
     */
    private TokenUsage tokenUsage;

    /**
     * 是否命中缓存
     * 为什么需要：监控缓存命中率，评估缓存策略有效性
     */
    private boolean cacheHit;

    /**
     * 实际重试次数
     * 为什么需要：区分首次成功和重试后成功，用于评估系统稳定性
     */
    private int retryCount;

    // ==================== 内部类定义 ====================

    /**
     * 来源引用
     */
    @Data
    @Builder
    public static class SourceCitation {
        private String source;
        private String title;
        private Double score;
        private String snippet;
    }
    
    /**
     * 命令建议
     */
    @Data
    @Builder
    public static class CommandSuggestion {
        private String command;
        private String description;
        private String riskLevel;
        private boolean requiresApproval;
    }
    
    /**
     * 诊断报告
     */
    @Data
    @Builder
    public static class DiagnosisReport {
        private String summary;
        private String rootCause;
        private List<String> evidence;
        private List<String> recommendations;
        private Map<String, Object> metrics;
    }

    /**
     * 工具调用记录
     * 为什么独立为内部类：每次 ReAct 循环中的 Action 步骤对应一条记录，
     * 包含调用参数、返回结果、耗时和成功状态，便于逐步回溯诊断过程
     */
    @Data
    @Builder
    public static class ToolCallRecord {
        /** 工具名称 */
        private String toolName;
        /** 调用参数 */
        private Map<String, Object> params;
        /** 返回结果 */
        private String result;
        /** 调用耗时（毫秒） */
        private long durationMs;
        /** 是否成功 */
        private boolean success;
    }

    /**
     * LLM Token 消耗统计
     * 为什么需要：LLM API 按 Token 计费，需要精确统计每次调用的消耗，
     * 用于成本分摊、预算告警和模型选择优化
     */
    @Data
    @Builder
    public static class TokenUsage {
        /** 输入 Token 数 */
        private int promptTokens;
        /** 输出 Token 数 */
        private int completionTokens;
        /** 总 Token 数 */
        private int totalTokens;
    }
}
