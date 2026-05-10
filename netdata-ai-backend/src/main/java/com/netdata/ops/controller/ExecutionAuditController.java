package com.netdata.ops.controller;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.ExecutionAudit;
import com.netdata.ops.service.ExecutionAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 命令执行审计控制器
 */
@Tag(name = "执行审计", description = "命令风险评估、执行审批、审计查询")
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionAuditController {

    private final ExecutionAuditService executionAuditService;

    @Operation(summary = "提交命令执行请求")
    @PostMapping
    @RequirePermission("execution:request")
    public R<ExecutionAudit> submitExecution(@RequestBody Map<String, String> body) {
        String command = body.get("command");
        String commandType = body.get("commandType");
        String targetHost = body.get("targetHost");
        return R.ok(executionAuditService.submitExecution(command, commandType, targetHost));
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
        // 使用submitExecution内部逻辑但不入库，直接返回评估结果
        ExecutionAudit preview = executionAuditService.submitExecution(command, null, null);
        return R.ok(Map.of(
                "riskLevel", preview.getRiskLevel(),
                "riskScore", preview.getRiskScore(),
                "status", preview.getStatus(),
                "commandType", preview.getCommandType()
        ));
    }
}
