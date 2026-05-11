package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netdata.ops.dto.request.UserCreateRequest;
import com.netdata.ops.dto.request.UserUpdateRequest;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.UserVO;
import com.netdata.ops.entity.SysUser;
import com.netdata.ops.entity.UserRole;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.SysUserMapper;
import com.netdata.ops.mapper.UserRoleMapper;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    private static final String USER_PERMS_CACHE_PREFIX = "user:perms:";

    /**
     * 分页查询用户列表
     */
    public PageResult<UserVO> getUserPage(int current, int size, String keyword) {
        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getDeleted, 0);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
                    .or().like(SysUser::getEmail, keyword));
        }
        wrapper.orderByDesc(SysUser::getCreatedAt);

        Page<SysUser> result = userMapper.selectPage(page, wrapper);

        List<UserVO> records = result.getRecords().stream()
                .map(this::toUserVO)
                .collect(Collectors.toList());

        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 获取用户详情
     */
    public UserVO getUserById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null || user.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return toUserVO(user);
    }

    /**
     * 创建用户
     */
    @Transactional
    public UserVO createUser(UserCreateRequest request) {
        // 检查用户名唯一
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // 检查邮箱唯一
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            SysUser existing = userMapper.selectByEmail(request.getEmail());
            if (existing != null) {
                throw new BusinessException(ErrorCode.EMAIL_EXISTS);
            }
        }

        // 创建用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(1);
        user.setLoginFailCount(0);
        user.setDeleted(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        // 分配角色
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            assignRoles(user.getId(), request.getRoleIds());
        }

        log.info("用户创建成功: {} by {}", request.getUsername(), SecurityUtils.getCurrentUsername());
        return toUserVO(user);
    }

    /**
     * 更新用户信息
     */
    @Transactional
    public UserVO updateUser(Long id, UserUpdateRequest request) {
        SysUser user = userMapper.selectById(id);
        if (user == null || user.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (request.getNickname() != null) user.setNickname(request.getNickname());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        if (request.getAvatar() != null) user.setAvatar(request.getAvatar());
        if (request.getStatus() != null) user.setStatus(request.getStatus());
        user.setUpdatedAt(LocalDateTime.now());

        userMapper.updateById(user);
        log.info("用户更新成功: id={} by {}", id, SecurityUtils.getCurrentUsername());
        return toUserVO(user);
    }

    /**
     * 逻辑删除用户
     */
    @Transactional
    public void deleteUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 不能删除自己
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不能删除当前登录用户");
        }

        user.setDeleted(1);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);

        // 清除权限缓存
        clearPermissionCache(id);
        log.info("用户删除成功: id={} by {}", id, SecurityUtils.getCurrentUsername());
    }

    /**
     * 为用户分配角色
     */
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        // 清除旧角色关联
        LambdaQueryWrapper<UserRole> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(UserRole::getUserId, userId);
        userRoleMapper.delete(deleteWrapper);

        // 新增角色关联
        Long currentUserId = SecurityUtils.getCurrentUserId();
        for (Long roleId : roleIds) {
            UserRole userRole = new UserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRole.setGrantedBy(currentUserId);
            userRole.setGrantedAt(LocalDateTime.now());
            userRoleMapper.insert(userRole);
        }

        // 清除权限缓存
        clearPermissionCache(userId);
        log.info("角色分配成功: userId={}, roleIds={} by {}", userId, roleIds, SecurityUtils.getCurrentUsername());
    }

    /**
     * 重置用户密码
     */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        com.netdata.ops.util.PasswordValidator.validate(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("密码重置成功: userId={} by {}", userId, SecurityUtils.getCurrentUsername());
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_WRONG);
        }

        com.netdata.ops.util.PasswordValidator.validate(newPassword);

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setIsFirstLogin(0);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (userId.equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不能修改自己的账户状态");
        }

        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("用户状态更新: userId={}, status={} by {}", userId, status, SecurityUtils.getCurrentUsername());
    }

    /**
     * 清除用户权限缓存
     */
    private void clearPermissionCache(Long userId) {
        redisTemplate.delete(USER_PERMS_CACHE_PREFIX + userId);
    }

    /**
     * 实体转VO
     */
    private UserVO toUserVO(SysUser user) {
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .lastLoginIp(user.getLastLoginIp())
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .build();
    }
}
