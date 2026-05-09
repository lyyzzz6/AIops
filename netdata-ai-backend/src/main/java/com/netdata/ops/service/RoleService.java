package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netdata.ops.entity.RolePermission;
import com.netdata.ops.entity.SysPermission;
import com.netdata.ops.entity.SysRole;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.RolePermissionMapper;
import com.netdata.ops.mapper.SysPermissionMapper;
import com.netdata.ops.mapper.SysRoleMapper;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    /**
     * 获取所有角色列表
     */
    public List<SysRole> listRoles() {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SysRole::getSortOrder);
        return roleMapper.selectList(wrapper);
    }

    /**
     * 获取角色详情
     */
    public SysRole getRoleById(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    /**
     * 创建角色
     */
    @Transactional
    public SysRole createRole(SysRole role) {
        // 检查编码唯一
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getRoleCode, role.getRoleCode());
        if (roleMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.DATA_ALREADY_EXISTS, "角色编码已存在");
        }

        role.setStatus(1);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);

        log.info("角色创建成功: {} by {}", role.getRoleCode(), SecurityUtils.getCurrentUsername());
        return role;
    }

    /**
     * 更新角色
     */
    @Transactional
    public SysRole updateRole(Long id, SysRole updated) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }

        if (updated.getRoleName() != null) role.setRoleName(updated.getRoleName());
        if (updated.getDescription() != null) role.setDescription(updated.getDescription());
        if (updated.getSortOrder() != null) role.setSortOrder(updated.getSortOrder());
        if (updated.getStatus() != null) role.setStatus(updated.getStatus());
        role.setUpdatedAt(LocalDateTime.now());

        roleMapper.updateById(role);
        return role;
    }

    /**
     * 获取角色的权限列表
     */
    public List<SysPermission> getRolePermissions(Long roleId) {
        return permissionMapper.selectPermissionsByRoleId(roleId);
    }

    /**
     * 给角色分配权限
     */
    @Transactional
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        // 验证角色存在
        if (roleMapper.selectById(roleId) == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }

        // 清除旧权限
        LambdaQueryWrapper<RolePermission> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(RolePermission::getRoleId, roleId);
        rolePermissionMapper.delete(deleteWrapper);

        // 分配新权限
        for (Long permId : permissionIds) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permId);
            rp.setGrantedAt(LocalDateTime.now());
            rolePermissionMapper.insert(rp);
        }

        log.info("角色权限分配成功: roleId={}, permissions={} by {}",
                roleId, permissionIds.size(), SecurityUtils.getCurrentUsername());
    }

    /**
     * 获取所有权限列表
     */
    public List<SysPermission> listAllPermissions() {
        return permissionMapper.selectList(null);
    }
}
