package com.netdata.ops.util;

import com.netdata.ops.security.SecurityUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全工具类 - 获取当前登录用户信息
 */
public class SecurityUtils {

    private static final String SUPER_ADMIN_CODE = "SUPER_ADMIN";

    private SecurityUtils() {}

    /**
     * 获取当前登录用户
     */
    public static SecurityUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
            return (SecurityUser) authentication.getPrincipal();
        }
        return null;
    }

    /**
     * 获取当前登录用户ID
     */
    public static Long getCurrentUserId() {
        SecurityUser user = getCurrentUser();
        return user != null ? user.getUserId() : null;
    }

    /**
     * 获取当前登录用户名
     */
    public static String getCurrentUsername() {
        SecurityUser user = getCurrentUser();
        return user != null ? user.getUsername() : null;
    }

    /**
     * 检查当前用户是否拥有指定权限
     */
    public static boolean hasPermission(String permissionCode) {
        SecurityUser user = getCurrentUser();
        if (user == null) return false;
        return user.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals(permissionCode));
    }

    /**
     * 检查当前用户是否拥有指定角色
     */
    public static boolean hasRole(String roleCode) {
        SecurityUser user = getCurrentUser();
        if (user == null) return false;
        return user.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_" + roleCode));
    }

    public static boolean isSuperAdmin() {
        return hasRole(SUPER_ADMIN_CODE);
    }

    public static boolean isAdminAccessAllowed() {
        return isSuperAdmin();
    }
}
