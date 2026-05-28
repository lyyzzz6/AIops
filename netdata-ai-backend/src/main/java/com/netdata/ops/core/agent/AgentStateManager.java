package com.netdata.ops.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * ============================================================
 * Agent 执行状态管理器
 * ============================================================
 *
 * 设计目的：
 * 基于 Redis 实现 Agent 执行状态的持久化管理，提供：
 * 1. 执行状态保存与查询（RUNNING → COMPLETED / FAILED / TIMEOUT）
 * 2. 审批流程状态机（PENDING → APPROVED / REJECTED → EXECUTED）
 * 3. TTL 过期自动清理（默认 24 小时）
 * 4. 命令执行审计日志记录
 *
 * 线程安全：依赖 Redis 原子操作，本身无可变状态。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class AgentStateManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 执行状态 Redis Key 前缀
     */
    private static final String STATE_PREFIX = "agent:state:";

    /**
     * 审批请求 Redis Key 前缀
     */
    private static final String APPROVAL_PREFIX = "agent:approval:";

    /**
     * 命令审计日志 Redis Key 前缀
     */
    private static final String AUDIT_PREFIX = "agent:audit:";

    /**
     * 审计日志列表 Key（用于存储最近的审计记录）
     */
    private static final String AUDIT_LIST_KEY = "agent:audit:list";

    /**
     * 默认 TTL（24 小时后自动清理）
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 审批超时时间（30 分钟）
     */
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(30);

    public AgentStateManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ==================== 执行状态管理 ====================

    /**
     * 保存执行状态到 Redis
     *
     * @param traceId 链路追踪 ID
     * @param state   执行状态对象
     */
    public void saveState(String traceId, AgentExecutionState state) {
        try {
            String key = STATE_PREFIX + traceId;
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
            log.debug("保存 Agent 执行状态: traceId={}, status={}", traceId, state.getStatus());
        } catch (JsonProcessingException e) {
            log.error("序列化执行状态失败: traceId={}", traceId, e);
        }
    }

    /**
     * 获取执行状态
     *
     * @param traceId 链路追踪 ID
     * @return 执行状态对象，不存在时返回 null
     */
    public AgentExecutionState getState(String traceId) {
        try {
            String key = STATE_PREFIX + traceId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, AgentExecutionState.class);
        } catch (JsonProcessingException e) {
            log.error("反序列化执行状态失败: traceId={}", traceId, e);
            return null;
        }
    }

    // ==================== 审批流程管理 ====================

    /**
     * 创建审批请求
     * 
     * 状态初始化为 PENDING，设置 30 分钟超时时间。
     *
     * @param request 审批请求（approvalId 会自动生成）
     * @return 审批请求 ID
     */
    public String createApprovalRequest(ApprovalRequest request) {
        String approvalId = UUID.randomUUID().toString().replace("-", "");
        request.setApprovalId(approvalId);
        request.setStatus(ApprovalStatus.PENDING);
        request.setCreatedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        request.setExpiresAt(Instant.now().plus(APPROVAL_TIMEOUT));

        try {
            String key = APPROVAL_PREFIX + approvalId;
            String json = objectMapper.writeValueAsString(request);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
            log.info("创建审批请求: approvalId={}, command={}, riskLevel={}",
                    approvalId, request.getCommand(), request.getRiskLevel());
        } catch (JsonProcessingException e) {
            log.error("序列化审批请求失败: approvalId={}", approvalId, e);
        }

        return approvalId;
    }

    /**
     * 更新审批状态
     *
     * 状态流转规则：
     * - PENDING → APPROVED / REJECTED / EXPIRED
     * - APPROVED → EXECUTED
     *
     * @param approvalId 审批请求 ID
     * @param status     目标状态
     * @param approver   审批人
     */
    public void updateApprovalStatus(String approvalId, ApprovalStatus status, String approver) {
        ApprovalRequest request = getApprovalRequest(approvalId);
        if (request == null) {
            log.warn("审批请求不存在: approvalId={}", approvalId);
            return;
        }

        // 校验状态流转合法性
        if (!isValidTransition(request.getStatus(), status)) {
            log.warn("非法状态流转: approvalId={}, from={}, to={}",
                    approvalId, request.getStatus(), status);
            return;
        }

        request.setStatus(status);
        request.setApprover(approver);
        request.setUpdatedAt(Instant.now());

        try {
            String key = APPROVAL_PREFIX + approvalId;
            String json = objectMapper.writeValueAsString(request);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
            log.info("更新审批状态: approvalId={}, status={}, approver={}",
                    approvalId, status, approver);
        } catch (JsonProcessingException e) {
            log.error("序列化审批请求失败: approvalId={}", approvalId, e);
        }
    }

    /**
     * 获取审批请求
     *
     * @param approvalId 审批请求 ID
     * @return 审批请求对象，不存在时返回 null
     */
    public ApprovalRequest getApprovalRequest(String approvalId) {
        try {
            String key = APPROVAL_PREFIX + approvalId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            ApprovalRequest request = objectMapper.readValue(json, ApprovalRequest.class);

            // 检查是否超时
            if (request.getStatus() == ApprovalStatus.PENDING
                    && request.getExpiresAt() != null
                    && Instant.now().isAfter(request.getExpiresAt())) {
                log.info("审批请求已超时，自动标记为 EXPIRED: approvalId={}", approvalId);
                request.setStatus(ApprovalStatus.EXPIRED);
                request.setUpdatedAt(Instant.now());
                String updatedJson = objectMapper.writeValueAsString(request);
                redisTemplate.opsForValue().set(key, updatedJson, DEFAULT_TTL);
            }

            return request;
        } catch (JsonProcessingException e) {
            log.error("反序列化审批请求失败: approvalId={}", approvalId, e);
            return null;
        }
    }

    /**
     * 校验状态流转合法性
     */
    private boolean isValidTransition(ApprovalStatus current, ApprovalStatus target) {
        if (current == null) {
            return false;
        }
        switch (current) {
            case PENDING:
                return target == ApprovalStatus.APPROVED
                        || target == ApprovalStatus.REJECTED
                        || target == ApprovalStatus.EXPIRED;
            case APPROVED:
                return target == ApprovalStatus.EXECUTED;
            default:
                return false;
        }
    }

    // ==================== 命令执行审计日志 ====================

    /**
     * 记录命令执行审计日志
     *
     * @param command 执行的命令
     * @param approved 是否已经审批
     */
    public void recordCommandExecution(String command, boolean approved) {
        try {
            CommandAuditLog auditLog = CommandAuditLog.builder()
                    .id(UUID.randomUUID().toString())
                    .command(command)
                    .approved(approved)
                    .executedAt(Instant.now())
                    .build();

            String json = objectMapper.writeValueAsString(auditLog);
            String key = AUDIT_PREFIX + auditLog.getId();

            // 存储单条审计记录
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(90)); // 审计日志保留90天

            // 添加到审计日志列表（按时间排序）
            redisTemplate.opsForList().leftPush(AUDIT_LIST_KEY, key);
            redisTemplate.opsForList().trim(AUDIT_LIST_KEY, 0, 999); // 只保留最近1000条

            log.info("[审计] 命令执行记录已保存: command={}, approved={}, auditId={}",
                    command, approved, auditLog.getId());
        } catch (JsonProcessingException e) {
            log.error("[审计] 保存命令执行审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 获取最近的审计日志
     *
     * @param limit 返回记录数量
     * @return 审计日志列表
     */
    public java.util.List<CommandAuditLog> getRecentAuditLogs(int limit) {
        java.util.List<CommandAuditLog> logs = new java.util.ArrayList<>();

        try {
            java.util.List<String> keys = redisTemplate.opsForList().range(AUDIT_LIST_KEY, 0, limit - 1);
            if (keys != null) {
                for (String key : keys) {
                    String json = redisTemplate.opsForValue().get(key);
                    if (json != null) {
                        CommandAuditLog log = objectMapper.readValue(json, CommandAuditLog.class);
                        logs.add(log);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[审计] 获取审计日志失败: {}", e.getMessage());
        }

        return logs;
    }

    /**
     * 命令执行审计日志实体
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CommandAuditLog {
        /** 审计记录ID */
        private String id;
        /** 执行的命令 */
        private String command;
        /** 是否已经审批 */
        private boolean approved;
        /** 执行时间 */
        private Instant executedAt;
        /** 执行用户（可选） */
        private String executedBy;
        /** 执行结果（可选，成功/失败） */
        private String result;
        /** 退出码（可选） */
        private Integer exitCode;
    }

    // ==================== 枚举定义 ====================

    /**
     * 执行状态枚举
     */
    public enum ExecutionStatus {
        /** 运行中 */
        RUNNING,
        /** 执行成功 */
        COMPLETED,
        /** 执行失败 */
        FAILED,
        /** 执行超时 */
        TIMEOUT,
        /** 已取消 */
        CANCELLED
    }

    /**
     * 审批状态枚举
     */
    public enum ApprovalStatus {
        /** 待审批 */
        PENDING,
        /** 已通过 */
        APPROVED,
        /** 已拒绝 */
        REJECTED,
        /** 已过期 */
        EXPIRED,
        /** 已执行 */
        EXECUTED
    }
}
