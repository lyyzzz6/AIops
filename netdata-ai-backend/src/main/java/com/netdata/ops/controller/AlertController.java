package com.netdata.ops.controller;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.AlertRecord;
import com.netdata.ops.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警管理控制器
 */
@Tag(name = "告警管理", description = "告警查询、确认、诊断、统计")
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @Operation(summary = "分页查询告警列表")
    @GetMapping
    @RequirePermission("alert:read")
    public R<PageResult<AlertRecord>> listAlerts(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String host,
            @RequestParam(required = false) String keyword) {
        return R.ok(alertService.getAlertPage(current, size, severity, status, host, keyword));
    }

    @Operation(summary = "获取告警详情")
    @GetMapping("/{id}")
    @RequirePermission("alert:read")
    public R<AlertRecord> getAlert(@PathVariable Long id) {
        return R.ok(alertService.getAlertById(id));
    }

    @Operation(summary = "确认/解决告警")
    @PutMapping("/{id}/resolve")
    @RequirePermission("alert:write")
    public R<AlertRecord> resolveAlert(@PathVariable Long id,
                                        @RequestBody Map<String, String> body) {
        String diagnosisResult = body.get("diagnosisResult");
        return R.ok("告警已解决", alertService.resolveAlert(id, diagnosisResult));
    }

    @Operation(summary = "批量解决告警")
    @PutMapping("/batch-resolve")
    @RequirePermission("alert:write")
    public R<Map<String, Object>> batchResolve(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Integer>) body.get("ids")).stream()
                .map(Integer::longValue)
                .toList();
        String diagnosisResult = (String) body.get("diagnosisResult");
        int count = alertService.batchResolve(ids, diagnosisResult);
        return R.ok(Map.of("resolved", count));
    }

    @Operation(summary = "接收外部告警webhook")
    @PostMapping("/webhook")
    @RequirePermission("alert:write")
    public R<AlertRecord> receiveAlert(@RequestBody Map<String, String> body) {
        AlertRecord alert = alertService.createAlert(
                body.get("alertId"),
                body.get("source"),
                body.get("severity"),
                body.get("alertName"),
                body.get("message"),
                body.get("host"),
                body.get("metricName"),
                body.get("metricValue"),
                body.get("threshold")
        );
        return R.ok("告警接收成功", alert);
    }

    @Operation(summary = "告警统计概览")
    @GetMapping("/stats")
    @RequirePermission("alert:read")
    public R<Map<String, Object>> getStats() {
        return R.ok(alertService.getAlertStats());
    }

    @Operation(summary = "告警趋势（最近7天）")
    @GetMapping("/trend")
    @RequirePermission("alert:read")
    public R<List<Map<String, Object>>> getTrend() {
        return R.ok(alertService.getAlertTrend());
    }

    @Operation(summary = "触发AI智能诊断")
    @PostMapping("/{id}/diagnose")
    @RequirePermission("alert:execute")
    public R<Map<String, Object>> triggerDiagnosis(@PathVariable Long id) {
        return R.ok("诊断完成", alertService.triggerDiagnosis(id));
    }
}
