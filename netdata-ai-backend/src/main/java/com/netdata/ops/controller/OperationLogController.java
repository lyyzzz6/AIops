package com.netdata.ops.controller;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.OperationLog;
import com.netdata.ops.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 操作日志控制器
 */
@Tag(name = "操作日志", description = "操作审计日志查询与统计")
@RestController
@RequestMapping("/api/v1/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    @Operation(summary = "分页查询操作日志")
    @GetMapping
    @RequirePermission("audit:read")
    public R<PageResult<OperationLog>> listLogs(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return R.ok(operationLogService.getLogPage(current, size, module, action, username, startTime, endTime));
    }

    @Operation(summary = "操作日志统计")
    @GetMapping("/stats")
    @RequirePermission("audit:read")
    public R<Map<String, Object>> getStats() {
        return R.ok(operationLogService.getLogStats());
    }
}
