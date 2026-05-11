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

@Slf4j
@Aspect
@Component
public class PermissionAspect {

    private static final String SUPER_ADMIN_CODE = "SUPER_ADMIN";

    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        String permissionCode = requirePermission.value();

        if (SecurityUtils.hasRole(SUPER_ADMIN_CODE)) {
            return joinPoint.proceed();
        }

        if (!SecurityUtils.hasPermission(permissionCode)) {
            log.warn("权限不足: user={}, required={}", SecurityUtils.getCurrentUsername(), permissionCode);
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }

        return joinPoint.proceed();
    }
}
