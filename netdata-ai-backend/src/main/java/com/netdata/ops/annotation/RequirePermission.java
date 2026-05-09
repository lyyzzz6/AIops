package com.netdata.ops.annotation;

import java.lang.annotation.*;

/**
 * 权限检查注解
 * 标注在Controller方法上，验证当前用户是否具有指定权限
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /**
     * 权限编码，格式: module:action
     * 例如: "user:write", "knowledge:delete"
     */
    String value();
}
