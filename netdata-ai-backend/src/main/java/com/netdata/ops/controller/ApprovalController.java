package com.netdata.ops.controller;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.PermissionRequest;
import com.netdata.ops.service.ApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 审批工作流控制器
 */
@Tag(name = "审批工作流", description = "权限申请、审批、转交、查询")
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @Operation(summary = "提交权限申请")
    @PostMapping("/requests")
    @RequirePermission("approval:submit")
    public R<PermissionRequest> submitRequest(@RequestBody Map<String, Object> body) {
        String requestType = (String) body.get("requestType");
        Long targetUserId = body.get("targetUserId") != null ? ((Number) body.get("targetUserId")).longValue() : null;
        Long targetRoleId = body.get("targetRoleId") != null ? ((Number) body.get("targetRoleId")).longValue() : null;
        String targetPermissionIds = (String) body.get("targetPermissionIds");
        String reason = (String) body.get("reason");
        Integer durationHours = body.get("durationHours") != null ? ((Number) body.get("durationHours")).intValue() : null;

        PermissionRequest request = approvalService.submitRequest(
                requestType, targetUserId, targetRoleId, targetPermissionIds, reason, durationHours);
        return R.ok("申请已提交", request);
    }

    @Operation(summary = "审批通过")
    @PutMapping("/requests/{id}/approve")
    @RequirePermission("approval:approve")
    public R<PermissionRequest> approve(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String comment = body.get("comment");
        return R.ok("审批已通过", approvalService.approve(id, comment));
    }

    @Operation(summary = "审批拒绝")
    @PutMapping("/requests/{id}/reject")
    @RequirePermission("approval:approve")
    public R<PermissionRequest> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String rejectReason = body.get("rejectReason");
        return R.ok("审批已拒绝", approvalService.reject(id, rejectReason));
    }

    @Operation(summary = "转交审批")
    @PutMapping("/requests/{id}/transfer")
    @RequirePermission("approval:approve")
    public R<PermissionRequest> transfer(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long transferToUserId = ((Number) body.get("transferToUserId")).longValue();
        String comment = (String) body.get("comment");
        return R.ok("审批已转交", approvalService.transfer(id, transferToUserId, comment));
    }

    @Operation(summary = "我的待审批列表")
    @GetMapping("/pending")
    @RequirePermission("approval:approve")
    public R<PageResult<PermissionRequest>> myPendingApprovals(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(approvalService.getMyPendingApprovals(current, size));
    }

    @Operation(summary = "我的申请记录")
    @GetMapping("/my-requests")
    @RequirePermission("approval:submit")
    public R<PageResult<PermissionRequest>> myRequests(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        return R.ok(approvalService.getMyRequests(current, size, status));
    }

    @Operation(summary = "所有审批记录（管理员）")
    @GetMapping("/requests")
    @RequirePermission("approval:approve")
    public R<PageResult<PermissionRequest>> allRequests(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String requestType) {
        return R.ok(approvalService.getAllRequests(current, size, status, requestType));
    }

    @Operation(summary = "获取审批详情（含审批流）")
    @GetMapping("/requests/{id}")
    @RequirePermission("approval:submit")
    public R<Map<String, Object>> getRequestDetail(@PathVariable Long id) {
        return R.ok(approvalService.getRequestDetail(id));
    }

    @Operation(summary = "审批统计")
    @GetMapping("/stats")
    @RequirePermission("approval:approve")
    public R<Map<String, Object>> getStats() {
        return R.ok(approvalService.getApprovalStats());
    }
}
