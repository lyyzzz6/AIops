package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.entity.*;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.*;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 审批工作流服务
 * 支持三种请求类型：角色分配(ROLE_ASSIGN)、权限授予(PERMISSION_GRANT)、临时提权(TEMP_ELEVATION)
 * 实现基于风险等级的多级审批流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final PermissionRequestMapper requestMapper;
    private final ApprovalFlowMapper flowMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;

    /**
     * 提交权限请求
     */
    @Transactional
    public PermissionRequest submitRequest(String requestType, Long targetUserId,
                                            Long targetRoleId, String targetPermissionIds,
                                            String reason, Integer durationHours) {
        Long requesterId = SecurityUtils.getCurrentUserId();

        // 不允许给自己审批
        if (requesterId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不能为自己提交权限请求");
        }

        // 验证目标用户存在
        if (targetUserId != null && userMapper.selectById(targetUserId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "目标用户不存在");
        }

        // 验证目标角色存在
        if (targetRoleId != null && roleMapper.selectById(targetRoleId) == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "目标角色不存在");
        }

        // 评估风险等级
        String riskLevel = assessRisk(requestType, targetRoleId, durationHours);

        // 确定审批人
        Long approverId = determineApprover(riskLevel, requesterId);

        PermissionRequest request = new PermissionRequest();
        request.setRequestNo(generateRequestNo());
        request.setRequesterId(requesterId);
        request.setRequestType(requestType);
        request.setTargetUserId(targetUserId);
        request.setTargetRoleId(targetRoleId);
        request.setTargetPermissionIds(targetPermissionIds);
        request.setReason(reason);
        request.setDurationHours(durationHours);
        request.setRiskLevel(riskLevel);
        request.setStatus("PENDING");
        request.setCurrentApproverId(approverId);
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());

        requestMapper.insert(request);

        // 创建审批流记录
        ApprovalFlow flow = new ApprovalFlow();
        flow.setRequestId(request.getId());
        flow.setStepOrder(1);
        flow.setApproverId(approverId);
        flow.setCreatedAt(LocalDateTime.now());
        flowMapper.insert(flow);

        log.info("权限请求已提交: requestNo={}, type={}, risk={}, approver={}",
                request.getRequestNo(), requestType, riskLevel, approverId);
        return request;
    }

    /**
     * 审批通过
     */
    @Transactional
    public PermissionRequest approve(Long requestId, String comment) {
        Long approverId = SecurityUtils.getCurrentUserId();
        PermissionRequest request = getAndValidateRequest(requestId, approverId);

        // 高风险需要二级审批
        if ("high".equals(request.getRiskLevel()) && needsSecondApproval(request)) {
            return escalateToSuperAdmin(request, approverId, comment);
        }

        // 更新请求状态
        request.setStatus("APPROVED");
        request.setApprovedBy(approverId);
        request.setApprovedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());

        // 临时提权设置过期时间
        if ("TEMP_ELEVATION".equals(request.getRequestType()) && request.getDurationHours() != null) {
            request.setExpiresAt(LocalDateTime.now().plusHours(request.getDurationHours()));
        }

        requestMapper.updateById(request);

        // 记录审批流
        updateApprovalFlow(requestId, approverId, "APPROVE", comment);

        // 执行权限变更
        executePermissionChange(request);

        log.info("权限请求已通过: requestNo={}, approver={}", request.getRequestNo(), approverId);
        return request;
    }

    /**
     * 审批拒绝
     */
    @Transactional
    public PermissionRequest reject(Long requestId, String rejectReason) {
        Long approverId = SecurityUtils.getCurrentUserId();
        PermissionRequest request = getAndValidateRequest(requestId, approverId);

        request.setStatus("REJECTED");
        request.setApprovedBy(approverId);
        request.setRejectReason(rejectReason);
        request.setApprovedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(request);

        // 记录审批流
        updateApprovalFlow(requestId, approverId, "REJECT", rejectReason);

        log.info("权限请求已拒绝: requestNo={}, reason={}", request.getRequestNo(), rejectReason);
        return request;
    }

    /**
     * 转交审批
     */
    @Transactional
    public PermissionRequest transfer(Long requestId, Long transferToUserId, String comment) {
        Long approverId = SecurityUtils.getCurrentUserId();
        PermissionRequest request = getAndValidateRequest(requestId, approverId);

        if (userMapper.selectById(transferToUserId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "转交目标用户不存在");
        }

        request.setCurrentApproverId(transferToUserId);
        request.setStatus("REVIEWING");
        request.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(request);

        // 记录转交流程
        updateApprovalFlow(requestId, approverId, "TRANSFER", comment);

        // 创建新的审批步骤
        LambdaQueryWrapper<ApprovalFlow> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(ApprovalFlow::getRequestId, requestId);
        long stepCount = flowMapper.selectCount(countWrapper);

        ApprovalFlow newFlow = new ApprovalFlow();
        newFlow.setRequestId(requestId);
        newFlow.setStepOrder((int) stepCount + 1);
        newFlow.setApproverId(transferToUserId);
        newFlow.setCreatedAt(LocalDateTime.now());
        flowMapper.insert(newFlow);

        log.info("审批已转交: requestNo={}, from={}, to={}", request.getRequestNo(), approverId, transferToUserId);
        return request;
    }

    /**
     * 查询我的待审批列表
     */
    public PageResult<PermissionRequest> getMyPendingApprovals(int current, int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Page<PermissionRequest> page = new Page<>(current, size);

        LambdaQueryWrapper<PermissionRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PermissionRequest::getCurrentApproverId, userId);
        wrapper.in(PermissionRequest::getStatus, "PENDING", "REVIEWING");
        wrapper.orderByDesc(PermissionRequest::getCreatedAt);

        Page<PermissionRequest> result = requestMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 查询我的申请记录
     */
    public PageResult<PermissionRequest> getMyRequests(int current, int size, String status) {
        Long userId = SecurityUtils.getCurrentUserId();
        Page<PermissionRequest> page = new Page<>(current, size);

        LambdaQueryWrapper<PermissionRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PermissionRequest::getRequesterId, userId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(PermissionRequest::getStatus, status);
        }
        wrapper.orderByDesc(PermissionRequest::getCreatedAt);

        Page<PermissionRequest> result = requestMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 全部审批记录（管理员查看）
     */
    public PageResult<PermissionRequest> getAllRequests(int current, int size,
                                                        String status, String requestType) {
        Page<PermissionRequest> page = new Page<>(current, size);

        LambdaQueryWrapper<PermissionRequest> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(PermissionRequest::getStatus, status);
        }
        if (requestType != null && !requestType.isBlank()) {
            wrapper.eq(PermissionRequest::getRequestType, requestType);
        }
        wrapper.orderByDesc(PermissionRequest::getCreatedAt);

        Page<PermissionRequest> result = requestMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 获取请求详情（含审批流）
     */
    public Map<String, Object> getRequestDetail(Long requestId) {
        PermissionRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "审批请求不存在");
        }

        LambdaQueryWrapper<ApprovalFlow> flowWrapper = new LambdaQueryWrapper<>();
        flowWrapper.eq(ApprovalFlow::getRequestId, requestId);
        flowWrapper.orderByAsc(ApprovalFlow::getStepOrder);
        List<ApprovalFlow> flows = flowMapper.selectList(flowWrapper);

        Map<String, Object> detail = new HashMap<>();
        detail.put("request", request);
        detail.put("approvalFlows", flows);

        // 补充用户名信息
        if (request.getRequesterId() != null) {
            SysUser requester = userMapper.selectById(request.getRequesterId());
            detail.put("requesterName", requester != null ? requester.getUsername() : "Unknown");
        }
        if (request.getTargetUserId() != null) {
            SysUser target = userMapper.selectById(request.getTargetUserId());
            detail.put("targetUserName", target != null ? target.getUsername() : "Unknown");
        }
        if (request.getTargetRoleId() != null) {
            SysRole role = roleMapper.selectById(request.getTargetRoleId());
            detail.put("targetRoleName", role != null ? role.getRoleName() : "Unknown");
        }

        return detail;
    }

    /**
     * 审批统计
     */
    public Map<String, Object> getApprovalStats() {
        Map<String, Object> stats = new HashMap<>();

        LambdaQueryWrapper<PermissionRequest> pendingWrapper = new LambdaQueryWrapper<>();
        pendingWrapper.in(PermissionRequest::getStatus, "PENDING", "REVIEWING");
        stats.put("pendingCount", requestMapper.selectCount(pendingWrapper));

        LambdaQueryWrapper<PermissionRequest> approvedWrapper = new LambdaQueryWrapper<>();
        approvedWrapper.eq(PermissionRequest::getStatus, "APPROVED");
        stats.put("approvedCount", requestMapper.selectCount(approvedWrapper));

        LambdaQueryWrapper<PermissionRequest> rejectedWrapper = new LambdaQueryWrapper<>();
        rejectedWrapper.eq(PermissionRequest::getStatus, "REJECTED");
        stats.put("rejectedCount", requestMapper.selectCount(rejectedWrapper));

        stats.put("totalCount", requestMapper.selectCount(null));

        return stats;
    }

    // ================= 私有方法 =================

    /**
     * 风险评估：根据请求类型和目标角色评估风险等级
     */
    private String assessRisk(String requestType, Long targetRoleId, Integer durationHours) {
        // 临时提权超过24h为高风险
        if ("TEMP_ELEVATION".equals(requestType) && durationHours != null && durationHours > 24) {
            return "high";
        }

        // 角色分配需检查目标角色等级
        if ("ROLE_ASSIGN".equals(requestType) && targetRoleId != null) {
            SysRole role = roleMapper.selectById(targetRoleId);
            if (role != null) {
                // SUPER_ADMIN/ADMIN角色为高风险
                if ("SUPER_ADMIN".equals(role.getRoleCode()) || "ADMIN".equals(role.getRoleCode())) {
                    return "high";
                }
                if ("OPERATOR".equals(role.getRoleCode())) {
                    return "medium";
                }
            }
        }

        // 默认低风险
        return "low";
    }

    /**
     * 确定审批人：基于风险等级路由
     * - low: 任意ADMIN
     * - medium: ADMIN
     * - high: SUPER_ADMIN
     */
    private Long determineApprover(String riskLevel, Long requesterId) {
        String requiredRole = "high".equals(riskLevel) ? "SUPER_ADMIN" : "ADMIN";

        // 查找拥有对应角色的用户（排除申请人自己）
        LambdaQueryWrapper<SysRole> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(SysRole::getRoleCode, requiredRole);
        SysRole role = roleMapper.selectOne(roleWrapper);

        if (role != null) {
            LambdaQueryWrapper<UserRole> urWrapper = new LambdaQueryWrapper<>();
            urWrapper.eq(UserRole::getRoleId, role.getId());
            urWrapper.ne(UserRole::getUserId, requesterId);
            List<UserRole> adminRoles = userRoleMapper.selectList(urWrapper);

            if (!adminRoles.isEmpty()) {
                return adminRoles.get(0).getUserId();
            }
        }

        // 回退：找任意SUPER_ADMIN
        LambdaQueryWrapper<SysRole> superWrapper = new LambdaQueryWrapper<>();
        superWrapper.eq(SysRole::getRoleCode, "SUPER_ADMIN");
        SysRole superRole = roleMapper.selectOne(superWrapper);
        if (superRole != null) {
            LambdaQueryWrapper<UserRole> urWrapper = new LambdaQueryWrapper<>();
            urWrapper.eq(UserRole::getRoleId, superRole.getId());
            List<UserRole> superAdmins = userRoleMapper.selectList(urWrapper);
            if (!superAdmins.isEmpty()) {
                return superAdmins.get(0).getUserId();
            }
        }

        // 极端情况：默认返回用户ID 1（系统管理员）
        return 1L;
    }

    /**
     * 判断是否需要二级审批
     */
    private boolean needsSecondApproval(PermissionRequest request) {
        LambdaQueryWrapper<ApprovalFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalFlow::getRequestId, request.getId());
        wrapper.eq(ApprovalFlow::getAction, "APPROVE");
        long approveCount = flowMapper.selectCount(wrapper);
        // 高风险需至少2次审批
        return approveCount < 1;
    }

    /**
     * 升级到超级管理员审批
     */
    private PermissionRequest escalateToSuperAdmin(PermissionRequest request, Long currentApproverId, String comment) {
        // 记录当前审批人的审批
        updateApprovalFlow(request.getId(), currentApproverId, "APPROVE", comment + " [需要上级审批]");

        // 找到SUPER_ADMIN
        Long superAdminId = determineApprover("high", currentApproverId);

        request.setStatus("REVIEWING");
        request.setCurrentApproverId(superAdminId);
        request.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(request);

        // 创建新审批步骤
        LambdaQueryWrapper<ApprovalFlow> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(ApprovalFlow::getRequestId, request.getId());
        long stepCount = flowMapper.selectCount(countWrapper);

        ApprovalFlow newFlow = new ApprovalFlow();
        newFlow.setRequestId(request.getId());
        newFlow.setStepOrder((int) stepCount + 1);
        newFlow.setApproverId(superAdminId);
        newFlow.setCreatedAt(LocalDateTime.now());
        flowMapper.insert(newFlow);

        log.info("高风险请求升级审批: requestNo={}, escalatedTo={}", request.getRequestNo(), superAdminId);
        return request;
    }

    /**
     * 执行权限变更（审批通过后）
     */
    private void executePermissionChange(PermissionRequest request) {
        switch (request.getRequestType()) {
            case "ROLE_ASSIGN" -> executeRoleAssign(request);
            case "PERMISSION_GRANT" -> executePermissionGrant(request);
            case "TEMP_ELEVATION" -> executeTempElevation(request);
            default -> log.warn("未知的请求类型: {}", request.getRequestType());
        }
    }

    private void executeRoleAssign(PermissionRequest request) {
        UserRole userRole = new UserRole();
        userRole.setUserId(request.getTargetUserId());
        userRole.setRoleId(request.getTargetRoleId());
        userRole.setGrantedBy(request.getApprovedBy());
        userRole.setGrantedAt(LocalDateTime.now());
        userRoleMapper.insert(userRole);
        log.info("角色分配已执行: userId={}, roleId={}", request.getTargetUserId(), request.getTargetRoleId());
    }

    private void executePermissionGrant(PermissionRequest request) {
        // 权限直接授予角色（通过关联表）
        if (request.getTargetPermissionIds() != null && request.getTargetRoleId() != null) {
            String[] permIds = request.getTargetPermissionIds().replace("[", "").replace("]", "").split(",");
            for (String permId : permIds) {
                try {
                    RolePermission rp = new RolePermission();
                    rp.setRoleId(request.getTargetRoleId());
                    rp.setPermissionId(Long.parseLong(permId.trim()));
                    rolePermissionMapper.insert(rp);
                } catch (NumberFormatException e) {
                    log.warn("无效的权限ID: {}", permId);
                }
            }
            log.info("权限授予已执行: roleId={}, permissions={}", request.getTargetRoleId(), request.getTargetPermissionIds());
        }
    }

    private void executeTempElevation(PermissionRequest request) {
        UserRole userRole = new UserRole();
        userRole.setUserId(request.getTargetUserId());
        userRole.setRoleId(request.getTargetRoleId());
        userRole.setGrantedBy(request.getApprovedBy());
        userRole.setGrantedAt(LocalDateTime.now());
        userRole.setExpiresAt(request.getExpiresAt());
        userRoleMapper.insert(userRole);
        log.info("临时提权已执行: userId={}, roleId={}, expiresAt={}",
                request.getTargetUserId(), request.getTargetRoleId(), request.getExpiresAt());
    }

    private PermissionRequest getAndValidateRequest(Long requestId, Long approverId) {
        PermissionRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "审批请求不存在");
        }
        if (!approverId.equals(request.getCurrentApproverId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "您不是当前审批人");
        }
        if (!"PENDING".equals(request.getStatus()) && !"REVIEWING".equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请求已处理，状态: " + request.getStatus());
        }
        return request;
    }

    private void updateApprovalFlow(Long requestId, Long approverId, String action, String comment) {
        LambdaQueryWrapper<ApprovalFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalFlow::getRequestId, requestId);
        wrapper.eq(ApprovalFlow::getApproverId, approverId);
        wrapper.isNull(ApprovalFlow::getAction);
        ApprovalFlow flow = flowMapper.selectOne(wrapper);

        if (flow != null) {
            flow.setAction(action);
            flow.setComment(comment);
            flow.setActedAt(LocalDateTime.now());
            flowMapper.updateById(flow);
        }
    }

    private String generateRequestNo() {
        return "REQ" + System.currentTimeMillis() + String.format("%04d", new Random().nextInt(10000));
    }
}
