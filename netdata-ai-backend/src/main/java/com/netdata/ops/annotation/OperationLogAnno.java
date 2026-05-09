package com.netdata.ops.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * 标注在Controller方法上，AOP自动记录操作日志到operation_log表
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLogAnno {

    /**
     * 模块名称
     */
    String module() default "";

    /**
     * 操作类型：CREATE, UPDATE, DELETE, QUERY, IMPORT, EXPORT, LOGIN, LOGOUT
     */
    String action() default "";

    /**
     * 操作描述
     */
    String description() default "";
}
