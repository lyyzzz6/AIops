package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.entity.ExecutionAudit;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.ExecutionAuditMapper;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 命令执行审计服务
 * 对AI Agent生成的运维命令进行风险评估和执行审计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionAuditService {

    private final ExecutionAuditMapper auditMapper;

    /**
     * 高危命令模式
     */
    private static final List<Pattern> CRITICAL_PATTERNS = List.of(
            Pattern.compile("rm\\s+-rf\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("dd\\s+if=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mkfs\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":(){ :|:& };:", Pattern.LITERAL),
            Pattern.compile("shutdown|reboot|halt|poweroff", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+(DATABASE|TABLE)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("TRUNCATE\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DELETE\\s+FROM\\s+\\w+\\s*;?$", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> HIGH_PATTERNS = List.of(
            Pattern.compile("rm\\s+-rf", Pattern.CASE_INSENSITIVE),
            Pattern.compile("systemctl\\s+(stop|disable|restart)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("kill\\s+-9", Pattern.CASE_INSENSITIVE),
            Pattern.compile("iptables\\s+-F", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chmod\\s+777", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chown\\s+-R", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> MEDIUM_PATTERNS = List.of(
            Pattern.compile("systemctl\\s+start", Pattern.CASE_INSENSITIVE),
            Pattern.compile("apt\\s+(install|remove|purge)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("yum\\s+(install|remove)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("docker\\s+(stop|rm|rmi)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("UPDATE\\s+\\w+\\s+SET", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 提交命令执行请求（含自动风险评估）
     */
    @Transactional
    public ExecutionAudit submitExecution(String command, String commandType, String targetHost) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 风险评估
        Map<String, Object> riskAssessment = assessCommandRisk(command);
        String riskLevel = (String) riskAssessment.get("riskLevel");
        int riskScore = (int) riskAssessment.get("riskScore");

        String requestId = "EXEC" + System.currentTimeMillis();

        ExecutionAudit audit = new ExecutionAudit();
        audit.setRequestId(requestId);
        audit.setUserId(userId);
        audit.setCommand(command);
        audit.setCommandType(commandType != null ? commandType : detectCommandType(command));
        audit.setTargetHost(targetHost);
        audit.setRiskLevel(riskLevel);
        audit.setRiskScore(riskScore);
        audit.setCreatedAt(LocalDateTime.now());
        audit.setUpdatedAt(LocalDateTime.now());

        // 低风险自动执行，中高风险需审批
        if ("low".equals(riskLevel)) {
            audit.setStatus("approved");
            audit.setApproverId(userId); // 自审批
            audit.setApprovedAt(LocalDateTime.now());
        } else if ("critical".equals(riskLevel)) {
            audit.setStatus("rejected");
            log.warn("危险命令被自动拦截: command={}, user={}", command, SecurityUtils.getCurrentUsername());
        } else {
            audit.setStatus("pending");
        }

        auditMapper.insert(audit);
        log.info("执行审计记录创建: requestId={}, riskLevel={}, riskScore={}, status={}",
                requestId, riskLevel, riskScore, audit.getStatus());
        return audit;
    }

    /**
     * 审批命令执行
     */
    @Transactional
    public ExecutionAudit approveExecution(Long auditId) {
        Long approverId = SecurityUtils.getCurrentUserId();
        ExecutionAudit audit = auditMapper.selectById(auditId);

        if (audit == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "审计记录不存在");
        }
        if (!"pending".equals(audit.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该请求已被处理");
        }

        audit.setStatus("approved");
        audit.setApproverId(approverId);
        audit.setApprovedAt(LocalDateTime.now());
        audit.setUpdatedAt(LocalDateTime.now());
        auditMapper.updateById(audit);

        log.info("命令执行已批准: requestId={}, approver={}", audit.getRequestId(), approverId);
        return audit;
    }

    /**
     * 拒绝命令执行
     */
    @Transactional
    public ExecutionAudit rejectExecution(Long auditId, String reason) {
        Long approverId = SecurityUtils.getCurrentUserId();
        ExecutionAudit audit = auditMapper.selectById(auditId);

        if (audit == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "审计记录不存在");
        }
        if (!"pending".equals(audit.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该请求已被处理");
        }

        audit.setStatus("rejected");
        audit.setApproverId(approverId);
        audit.setApprovedAt(LocalDateTime.now());
        audit.setExecutionResult("拒绝原因: " + reason);
        audit.setUpdatedAt(LocalDateTime.now());
        auditMapper.updateById(audit);

        log.info("命令执行已拒绝: requestId={}, reason={}", audit.getRequestId(), reason);
        return audit;
    }

    /**
     * 记录执行结果
     */
    @Transactional
    public ExecutionAudit recordResult(Long auditId, String result, boolean success) {
        ExecutionAudit audit = auditMapper.selectById(auditId);
        if (audit == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "审计记录不存在");
        }

        audit.setStatus(success ? "executed" : "failed");
        audit.setExecutionResult(result);
        audit.setExecutedAt(LocalDateTime.now());
        audit.setUpdatedAt(LocalDateTime.now());
        auditMapper.updateById(audit);

        return audit;
    }

    /**
     * 分页查询执行审计记录
     */
    public PageResult<ExecutionAudit> getAuditPage(int current, int size,
                                                    String status, String riskLevel, String targetHost) {
        Page<ExecutionAudit> page = new Page<>(current, size);
        LambdaQueryWrapper<ExecutionAudit> wrapper = new LambdaQueryWrapper<>();

        if (status != null && !status.isBlank()) {
            wrapper.eq(ExecutionAudit::getStatus, status);
        }
        if (riskLevel != null && !riskLevel.isBlank()) {
            wrapper.eq(ExecutionAudit::getRiskLevel, riskLevel);
        }
        if (targetHost != null && !targetHost.isBlank()) {
            wrapper.eq(ExecutionAudit::getTargetHost, targetHost);
        }
        wrapper.orderByDesc(ExecutionAudit::getCreatedAt);

        Page<ExecutionAudit> result = auditMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 执行审计统计
     */
    public Map<String, Object> getAuditStats() {
        Map<String, Object> stats = new HashMap<>();

        LambdaQueryWrapper<ExecutionAudit> pendingWrapper = new LambdaQueryWrapper<>();
        pendingWrapper.eq(ExecutionAudit::getStatus, "pending");
        stats.put("pendingCount", auditMapper.selectCount(pendingWrapper));

        LambdaQueryWrapper<ExecutionAudit> executedWrapper = new LambdaQueryWrapper<>();
        executedWrapper.eq(ExecutionAudit::getStatus, "executed");
        stats.put("executedCount", auditMapper.selectCount(executedWrapper));

        LambdaQueryWrapper<ExecutionAudit> rejectedWrapper = new LambdaQueryWrapper<>();
        rejectedWrapper.eq(ExecutionAudit::getStatus, "rejected");
        stats.put("rejectedCount", auditMapper.selectCount(rejectedWrapper));

        LambdaQueryWrapper<ExecutionAudit> failedWrapper = new LambdaQueryWrapper<>();
        failedWrapper.eq(ExecutionAudit::getStatus, "failed");
        stats.put("failedCount", auditMapper.selectCount(failedWrapper));

        stats.put("totalCount", auditMapper.selectCount(null));

        // 风险分布
        for (String level : List.of("low", "medium", "high", "critical")) {
            LambdaQueryWrapper<ExecutionAudit> riskWrapper = new LambdaQueryWrapper<>();
            riskWrapper.eq(ExecutionAudit::getRiskLevel, level);
            stats.put(level + "RiskCount", auditMapper.selectCount(riskWrapper));
        }

        return stats;
    }

    // ================= 私有方法 =================

    /**
     * 命令风险评估
     */
    private Map<String, Object> assessCommandRisk(String command) {
        Map<String, Object> result = new HashMap<>();

        // critical级别检测
        for (Pattern pattern : CRITICAL_PATTERNS) {
            if (pattern.matcher(command).find()) {
                result.put("riskLevel", "critical");
                result.put("riskScore", 100);
                result.put("matchedPattern", pattern.pattern());
                return result;
            }
        }

        // high级别检测
        for (Pattern pattern : HIGH_PATTERNS) {
            if (pattern.matcher(command).find()) {
                result.put("riskLevel", "high");
                result.put("riskScore", 75);
                result.put("matchedPattern", pattern.pattern());
                return result;
            }
        }

        // medium级别检测
        for (Pattern pattern : MEDIUM_PATTERNS) {
            if (pattern.matcher(command).find()) {
                result.put("riskLevel", "medium");
                result.put("riskScore", 50);
                result.put("matchedPattern", pattern.pattern());
                return result;
            }
        }

        // 默认低风险
        result.put("riskLevel", "low");
        result.put("riskScore", 10);
        result.put("matchedPattern", "none");
        return result;
    }

    /**
     * 自动检测命令类型
     */
    private String detectCommandType(String command) {
        if (command == null) return "unknown";
        String lower = command.toLowerCase().trim();

        if (lower.startsWith("select") || lower.startsWith("show") || lower.startsWith("describe")) {
            return "query";
        }
        if (lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete")) {
            return "sql_modify";
        }
        if (lower.startsWith("docker")) return "docker";
        if (lower.startsWith("systemctl") || lower.startsWith("service")) return "service_mgmt";
        if (lower.startsWith("apt") || lower.startsWith("yum") || lower.startsWith("dnf")) return "package_mgmt";
        if (lower.contains("netdata") || lower.contains("monitor")) return "monitoring";
        return "shell";
    }
}
