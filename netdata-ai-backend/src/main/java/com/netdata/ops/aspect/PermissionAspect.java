package com.netdata.ops.aspect;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 权限检查切面
 * 拦截带有@RequirePermission注解的方法，验证权限
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        String permissionCode = requirePermission.value();

        // SUPER_ADMIN角色拥有所有权限
        if (SecurityUtils.hasRole("SUPER_ADMIN")) {
            return joinPoint.proceed();
        }

        // 检查用户是否拥有指定权限
        if (!SecurityUtils.hasPermission(permissionCode)) {
            log.warn("权限不足: user={}, required={}", SecurityUtils.getCurrentUsername(), permissionCode);
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }

        return joinPoint.proceed();
    }
}
