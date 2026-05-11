package com.netdata.ops.controller;

import com.netdata.ops.annotation.AdminOnly;
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

@Tag(name = "角色管理", description = "角色CRUD、权限分配")
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@AdminOnly
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "获取所有角色列表")
    @GetMapping
    @RequirePermission("role:read")
    public R<List<SysRole>> listRoles() {
        return R.ok(roleService.listRoles());
    }

    @Operation(summary = "获取角色详情")
    @GetMapping("/{id}")
    @RequirePermission("role:read")
    public R<SysRole> getRole(@PathVariable Long id) {
        return R.ok(roleService.getRoleById(id));
    }

    @Operation(summary = "创建角色")
    @PostMapping
    @RequirePermission("role:write")
    public R<SysRole> createRole(@RequestBody SysRole role) {
        return R.ok("角色创建成功", roleService.createRole(role));
    }

    @Operation(summary = "更新角色")
    @PutMapping("/{id}")
    @RequirePermission("role:write")
    public R<SysRole> updateRole(@PathVariable Long id, @RequestBody SysRole role) {
        return R.ok("角色更新成功", roleService.updateRole(id, role));
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    @RequirePermission("role:delete")
    public R<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return R.ok();
    }

    @Operation(summary = "获取角色的权限列表")
    @GetMapping("/{id}/permissions")
    @RequirePermission("role:read")
    public R<List<SysPermission>> getRolePermissions(@PathVariable Long id) {
        return R.ok(roleService.getRolePermissions(id));
    }

    @Operation(summary = "给角色分配权限")
    @PutMapping("/{id}/permissions")
    @RequirePermission("role:permission_assign")
    public R<Void> assignPermissions(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        roleService.assignPermissions(id, body.get("permissionIds"));
        return R.ok();
    }

    @Operation(summary = "获取所有权限列表")
    @GetMapping("/permissions/all")
    @RequirePermission("role:read")
    public R<List<SysPermission>> listAllPermissions() {
        return R.ok(roleService.listAllPermissions());
    }
}
