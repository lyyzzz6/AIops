package com.netdata.ops.controller;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.SysPermission;
import com.netdata.ops.entity.SysRole;
import com.netdata.ops.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 角色与权限管理控制器
 */
@Tag(name = "角色管理", description = "角色CRUD、权限分配")
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "获取所有角色列表")
    @GetMapping
    public R<List<SysRole>> listRoles() {
        return R.ok(roleService.listRoles());
    }

    @Operation(summary = "获取角色详情")
    @GetMapping("/{id}")
    public R<SysRole> getRole(@PathVariable Long id) {
        return R.ok(roleService.getRoleById(id));
    }

    @Operation(summary = "创建角色")
    @PostMapping
    @RequirePermission("system:config")
    public R<SysRole> createRole(@RequestBody SysRole role) {
        return R.ok("角色创建成功", roleService.createRole(role));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    @RequirePermission("system:config")
    public R<SysRole> updateRole(@PathVariable Long id, @RequestBody SysRole role) {
        return R.ok("角色更新成功", roleService.updateRole(id, role));
    }

    @Operation(summary = "获取角色的权限列表")
    @GetMapping("/{id}/permissions")
    public R<List<SysPermission>> getRolePermissions(@PathVariable Long id) {
        return R.ok(roleService.getRolePermissions(id));
    }

    @Operation(summary = "给角色分配权限")
    @PutMapping("/{id}/permissions")
    @RequirePermission("system:config")
    public R<Void> assignPermissions(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        roleService.assignPermissions(id, body.get("permissionIds"));
        return R.ok();
    }

    @Operation(summary = "获取所有权限列表")
    @GetMapping("/permissions/all")
    public R<List<SysPermission>> listAllPermissions() {
        return R.ok(roleService.listAllPermissions());
    }
}
