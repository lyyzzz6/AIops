package com.netdata.ops.exception;

import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
public enum ErrorCode {

    // 通用错误 1xxxx
    SUCCESS(200, "操作成功"),
    INTERNAL_ERROR(10001, "系统内部错误"),
    PARAM_INVALID(10002, "参数校验失败"),
    DATA_NOT_FOUND(10003, "数据不存在"),
    DATA_ALREADY_EXISTS(10004, "数据已存在"),

    // 认证错误 2xxxx
    UNAUTHORIZED(20001, "未认证，请先登录"),
    TOKEN_EXPIRED(20002, "Token已过期"),
    TOKEN_INVALID(20003, "Token无效"),
    LOGIN_FAILED(20004, "用户名或密码错误"),
    ACCOUNT_LOCKED(20005, "账户已锁定，请稍后再试"),
    ACCOUNT_DISABLED(20006, "账户已被禁用"),

    // 授权错误 3xxxx
    FORBIDDEN(30001, "无权限访问"),
    PERMISSION_DENIED(30002, "权限不足"),
    ROLE_NOT_FOUND(30003, "角色不存在"),

    // 用户模块 4xxxx
    USER_NOT_FOUND(40001, "用户不存在"),
    USERNAME_EXISTS(40002, "用户名已存在"),
    EMAIL_EXISTS(40003, "邮箱已被使用"),
    PASSWORD_INVALID(40004, "密码格式不正确"),
    OLD_PASSWORD_WRONG(40005, "原密码错误"),

    // 审批模块 5xxxx
    APPROVAL_NOT_FOUND(50001, "审批请求不存在"),
    APPROVAL_ALREADY_PROCESSED(50002, "审批请求已处理"),
    APPROVAL_NOT_AUTHORIZED(50003, "无权处理此审批"),
    APPROVAL_EXPIRED(50004, "审批请求已过期"),

    // 限流 6xxxx
    RATE_LIMIT_EXCEEDED(60001, "请求过于频繁，请稍后再试"),

    // 管理员访问 7xxxx
    ADMIN_ACCESS_DENIED(70001, "非超管用户无权访问管理端"),
    FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED(70002, "首次登录必须修改密码"),
    PASSWORD_COMPLEXITY_INVALID(70003, "密码复杂度不足，需包含大小写字母、数字和特殊字符，长度8-32位"),
    SESSION_TIMEOUT(70004, "会话已超时，请重新登录");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
