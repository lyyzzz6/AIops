package com.netdata.ops.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.annotation.OperationLogAnno;
import com.netdata.ops.entity.OperationLog;
import com.netdata.ops.mapper.OperationLogMapper;
import com.netdata.ops.util.IpUtils;
import com.netdata.ops.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 操作日志AOP切面
 * 拦截带有@OperationLogAnno注解的方法，自动记录操作日志
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operationLogAnno)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLogAnno operationLogAnno) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = null;
        Exception exception = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            saveLog(joinPoint, operationLogAnno, result, exception, executionTime);
        }
    }

    private void saveLog(ProceedingJoinPoint joinPoint, OperationLogAnno annotation,
                         Object result, Exception exception, long executionTime) {
        try {
            OperationLog opLog = new OperationLog();

            // 基本信息
            opLog.setTraceId(MDC.get("traceId"));
            opLog.setModule(annotation.module());
            opLog.setAction(annotation.action());
            opLog.setDescription(annotation.description());
            opLog.setExecutionTimeMs(executionTime);
            opLog.setCreatedAt(LocalDateTime.now());

            // 用户信息
            try {
                opLog.setUserId(SecurityUtils.getCurrentUserId());
                opLog.setUsername(SecurityUtils.getCurrentUsername());
            } catch (Exception e) {
                opLog.setUserId(0L);
                opLog.setUsername("anonymous");
            }

            // 请求信息
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                opLog.setRequestMethod(request.getMethod());
                opLog.setRequestUrl(request.getRequestURI());
                opLog.setIpAddress(IpUtils.getClientIp(request));
                opLog.setUserAgent(request.getHeader("User-Agent"));

                // 请求参数（限制长度，避免大body）
                String params = truncateParams(joinPoint);
                opLog.setRequestParams(params);
            }

            // 目标方法
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            opLog.setTarget(signature.getDeclaringTypeName() + "." + signature.getName());

            // 结果状态
            if (exception != null) {
                opLog.setStatus(0);
                opLog.setErrorMessage(truncate(exception.getMessage(), 500));
            } else {
                opLog.setStatus(1);
            }

            // 异步保存（不影响主流程）
            operationLogMapper.insert(opLog);
        } catch (Exception e) {
            log.error("操作日志保存失败", e);
        }
    }

    private String truncateParams(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return "[]";
            String json = objectMapper.writeValueAsString(args);
            return truncate(json, 2000);
        } catch (Exception e) {
            return "[serialization error]";
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
