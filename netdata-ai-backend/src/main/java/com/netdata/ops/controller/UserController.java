package com.netdata.ops.controller;

import com.netdata.ops.annotation.AdminOnly;
import com.netdata.ops.annotation.OperationLogAnno;
import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.dto.request.UserCreateRequest;
import com.netdata.ops.dto.request.UserUpdateRequest;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.dto.response.UserVO;
import com.netdata.ops.service.UserService;
import com.netdata.ops.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "用户管理", description = "用户CRUD、角色分配、密码管理")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@AdminOnly
public class UserController {

    private final UserService userService;

    @Operation(summary = "分页查询用户列表")
    @GetMapping
    @RequirePermission("user:read")
    public R<PageResult<UserVO>> listUsers(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return R.ok(userService.getUserPage(current, size, keyword));
    }

    @Operation(summary = "获取用户详情")
    @GetMapping("/{id}")
    @RequirePermission("user:read")
    public R<UserVO> getUser(@PathVariable Long id) {
        return R.ok(userService.getUserById(id));
    }

    @Operation(summary = "创建用户")
    @PostMapping
    @RequirePermission("user:write")
    @OperationLogAnno(module = "用户管理", action = "CREATE", description = "创建用户")
    public R<UserVO> createUser(@Valid @RequestBody UserCreateRequest request) {
        return R.ok("用户创建成功", userService.createUser(request));
    }

    @Operation(summary = "更新用户信息")
    @PutMapping("/{id}")
    @RequirePermission("user:write")
    @OperationLogAnno(module = "用户管理", action = "UPDATE", description = "更新用户信息")
    public R<UserVO> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return R.ok("用户更新成功", userService.updateUser(id, request));
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    @RequirePermission("user:delete")
    @OperationLogAnno(module = "用户管理", action = "DELETE", description = "删除用户")
    public R<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return R.ok();
    }

    @Operation(summary = "为用户分配角色")
    @PostMapping("/{id}/roles")
    @RequirePermission("user:role_assign")
    @OperationLogAnno(module = "用户管理", action = "UPDATE", description = "为用户分配角色")
    public R<Void> assignRoles(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        List<Long> roleIds = body.get("roleIds");
        userService.assignRoles(id, roleIds);
        return R.ok();
    }

    @Operation(summary = "重置用户密码")
    @PutMapping("/{id}/password")
    @RequirePermission("user:write")
    @OperationLogAnno(module = "用户管理", action = "UPDATE", description = "重置用户密码")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("newPassword"));
        return R.ok();
    }

    @Operation(summary = "修改自己的密码")
    @PutMapping("/me/password")
    public R<Void> changeMyPassword(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        userService.changePassword(userId, body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }

    @Operation(summary = "启用/禁用用户")
    @PutMapping("/{id}/status")
    @RequirePermission("user:write")
    public R<Void> updateUserStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        userService.updateUserStatus(id, body.get("status"));
        return R.ok();
    }
}
