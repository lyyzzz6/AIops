package com.netdata.ops.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.slf4j.MDC;

import java.time.LocalDateTime;

/**
 * 统一响应包装类
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;
    private LocalDateTime timestamp;

    private R() {
        this.timestamp = LocalDateTime.now();
        this.traceId = MDC.get("traceId");
    }

    public static <T> R<T> ok() {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "success";
        return r;
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok(String message, T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> R<T> fail(String message) {
        return fail(500, message);
    }

    public static <T> R<T> unauthorized(String message) {
        return fail(401, message);
    }

    public static <T> R<T> forbidden(String message) {
        return fail(403, message);
    }

    public static <T> R<T> notFound(String message) {
        return fail(404, message);
    }

    public static <T> R<T> badRequest(String message) {
        return fail(400, message);
    }

    public static <T> R<T> tooManyRequests(String message) {
        return fail(429, message);
    }
}
