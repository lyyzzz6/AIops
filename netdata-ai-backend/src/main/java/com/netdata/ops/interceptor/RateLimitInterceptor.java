package com.netdata.ops.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.dto.response.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的滑动窗口限流拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${security.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${security.rate-limit.enabled:true}")
    private boolean enabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) {
            return true;
        }

        String clientKey = getClientKey(request);
        String redisKey = "rate_limit:" + clientKey;

        long currentTimeMs = System.currentTimeMillis();
        long windowStartMs = currentTimeMs - 60_000; // 1分钟滑动窗口

        // 使用Redis ZSET实现滑动窗口
        // 移除窗口之前的记录
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStartMs);

        // 统计当前窗口内的请求数
        Long count = redisTemplate.opsForZSet().zCard(redisKey);
        if (count != null && count >= requestsPerMinute) {
            log.warn("请求限流触发: client={}, count={}", clientKey, count);
            writeRateLimitResponse(response);
            return false;
        }

        // 添加当前请求记录
        redisTemplate.opsForZSet().add(redisKey, String.valueOf(currentTimeMs), currentTimeMs);
        redisTemplate.expire(redisKey, 2, TimeUnit.MINUTES); // 设置key过期

        // 设置响应头告知剩余配额
        response.setHeader("X-RateLimit-Limit", String.valueOf(requestsPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(requestsPerMinute - (count != null ? count + 1 : 1)));

        return true;
    }

    private String getClientKey(HttpServletRequest request) {
        // 优先使用认证用户ID，否则使用IP
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }

        // 获取客户端IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        // 取第一个IP（代理链中的客户端IP）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return "ip:" + ip;
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<?> result = R.fail(60001, "请求过于频繁，请稍后再试");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
