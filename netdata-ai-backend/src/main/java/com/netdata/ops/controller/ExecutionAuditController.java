package com.netdata.ops.controller;

import com.netdata.ops.annotation.AdminOnly;
import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.ExecutionAudit;
import com.netdata.ops.service.ExecutionAuditService;
import com.netdata.ops.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Tag(name = "执行审计", description = "命令风险评估、执行审批、审计查询")
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
@AdminOnly
public class ExecutionAuditController {

    private final ExecutionAuditService executionAuditService;

    @Operation(summary = "提交命令执行请求")
    @PostMapping
    @RequirePermission("execution:request")
    public R<ExecutionAudit> submitExecution(@RequestBody Map<String, String> body) {
        String command = body.get("command");
        String commandType = body.get("commandType");
        String targetHost = body.get("targetHost");
        log.info("[执行请求] 用户: {}, 命令: {}, 命令类型: {}, 目标主机: {}",
                SecurityUtils.getCurrentUsername(), command, commandType, targetHost);
        ExecutionAudit result = executionAuditService.submitExecution(command, commandType, targetHost);
        log.info("[执行请求] 请求已提交, requestId: {}, 风险等级: {}, 状态: {}",
                result.getRequestId(), result.getRiskLevel(), result.getStatus());
        return R.ok(result);
    }

    @Operation(summary = "审批通过命令执行")
    @PutMapping("/{id}/approve")
    @RequirePermission("execution:approve")
    public R<ExecutionAudit> approveExecution(@PathVariable Long id) {
        return R.ok("执行已批准", executionAuditService.approveExecution(id));
    }

    @Operation(summary = "拒绝命令执行")
    @PutMapping("/{id}/reject")
    @RequirePermission("execution:approve")
    public R<ExecutionAudit> rejectExecution(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        return R.ok("执行已拒绝", executionAuditService.rejectExecution(id, reason));
    }

    @Operation(summary = "记录执行结果")
    @PutMapping("/{id}/result")
    @RequirePermission("execution:request")
    public R<ExecutionAudit> recordResult(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String result = (String) body.get("result");
        boolean success = Boolean.TRUE.equals(body.get("success"));
        return R.ok(executionAuditService.recordResult(id, result, success));
    }

    @Operation(summary = "分页查询审计记录")
    @GetMapping
    @RequirePermission("execution:read")
    public R<PageResult<ExecutionAudit>> listAudits(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String targetHost) {
        return R.ok(executionAuditService.getAuditPage(current, size, status, riskLevel, targetHost));
    }

    @Operation(summary = "执行审计统计")
    @GetMapping("/stats")
    @RequirePermission("execution:read")
    public R<Map<String, Object>> getStats() {
        return R.ok(executionAuditService.getAuditStats());
    }

    @Operation(summary = "命令风险预评估")
    @PostMapping("/risk-assess")
    @RequirePermission("execution:request")
    public R<Map<String, Object>> riskAssess(@RequestBody Map<String, String> body) {
        String command = body.get("command");
        log.info("[风险评估] 用户: {}, 命令: {}", SecurityUtils.getCurrentUsername(), command);
        // 使用submitExecution内部逻辑但不入库，直接返回评估结果
        ExecutionAudit preview = executionAuditService.submitExecution(command, null, null);
        log.info("[风险评估] 结果 - 风险等级: {}, 风险分数: {}, 状态: {}",
                preview.getRiskLevel(), preview.getRiskScore(), preview.getStatus());
        return R.ok(Map.of(
                "riskLevel", preview.getRiskLevel(),
                "riskScore", preview.getRiskScore(),
                "status", preview.getStatus(),
                "commandType", preview.getCommandType()
        ));
    }
}
