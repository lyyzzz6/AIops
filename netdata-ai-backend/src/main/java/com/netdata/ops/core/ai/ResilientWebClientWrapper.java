package com.netdata.ops.core.ai;

import com.netdata.ops.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 弹性 WebClient 封装器
 *
 * <p>对 WebClient 调用 Python 异常检测服务进行封装，集成 Resilience4j 装饰器：
 * <ul>
 *   <li>重试：网络抖动时自动重试</li>
 *   <li>熔断：持续失败时快速失败，避免请求堆积</li>
 *   <li>超时：防止请求长时间挂起</li>
 *   <li>降级：失败时返回默认降级结果（如简单阈值判断）</li>
 * </ul>
 *
 * <p>设计理由：
 * <ol>
 *   <li>将容错逻辑与业务逻辑分离，服务调用方无需关心重试/熔断细节</li>
 *   <li>Python 服务不可用时，降级为简单规则判断，保证系统基本可用</li>
 *   <li>所有降级事件都记录日志，便于后续排查和监控</li>
 * </ol>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class ResilientWebClientWrapper {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public ResilientWebClientWrapper(
            WebClient.Builder webClientBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry) {
        this.webClient = webClientBuilder.build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ANOMALY_DETECTION);
        this.retry = retryRegistry.retry(ResilienceConfig.ANOMALY_DETECTION);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(ResilienceConfig.ANOMALY_DETECTION);

        // 注册事件监听器用于日志记录
        this.retry.getEventPublisher()
                .onRetry(event -> log.warn("[Python服务重试] 第{}次重试，等待{}ms",
                        event.getNumberOfRetryAttempts(),
                        event.getWaitInterval().toMillis()));

        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("[Python服务熔断器] 状态变更: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * 带容错的 GET 请求
     *
     * @param url          请求URL
     * @param responseType 响应类型
     * @param fallback     降级返回值
     * @param <T>          响应泛型
     * @return 响应结果或降级结果
     */
    public <T> T get(String url, Class<T> responseType, T fallback) {
        return executeWithResilience(
                () -> webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(responseType)
                        .block(Duration.ofSeconds(10)),
                fallback,
                url
        );
    }

    /**
     * 带容错的 POST 请求
     *
     * @param url          请求URL
     * @param body         请求体
     * @param responseType 响应类型
     * @param fallback     降级返回值
     * @param <T>          响应泛型
     * @return 响应结果或降级结果
     */
    public <T> T post(String url, Object body, Class<T> responseType, T fallback) {
        return executeWithResilience(
                () -> webClient.post()
                        .uri(url)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(responseType)
                        .block(Duration.ofSeconds(10)),
                fallback,
                url
        );
    }

    /**
     * 带容错的响应式 POST 请求（返回 Mono）
     *
     * @param url          请求URL
     * @param body         请求体
     * @param responseType 响应类型
     * @param fallback     降级返回值
     * @param <T>          响应泛型
     * @return 响应 Mono 或降级 Mono
     */
    public <T> Mono<T> postReactive(String url, Object body, Class<T> responseType, T fallback) {
        return Mono.defer(() -> webClient.post()
                        .uri(url)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(responseType))
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(throwable -> {
                    log.warn("[Python服务响应式降级] URL: {}, 原因: {}", url, throwable.getMessage());
                    return Mono.just(fallback);
                });
    }

    /**
     * 异常检测专用调用方法
     *
     * <p>当 Python 异常检测服务不可用时，降级为简单阈值判断结果
     *
     * @param serviceUrl 异常检测服务基础URL
     * @param metricData 指标数据
     * @return 检测结果 Map，降级时返回简单阈值判断结果
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> detectAnomaly(String serviceUrl, Map<String, Object> metricData) {
        Map<String, Object> fallbackResult = createAnomalyFallbackResult(metricData);

        return executeWithResilience(
                () -> webClient.post()
                        .uri(serviceUrl + "/api/v1/detect")
                        .bodyValue(metricData)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(Duration.ofSeconds(10)),
                fallbackResult,
                serviceUrl + "/api/v1/detect"
        );
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /**
     * 使用 Resilience4j 装饰器执行调用
     *
     * <p>装饰顺序：Retry → CircuitBreaker → 实际调用 → 降级
     */
    private <T> T executeWithResilience(Supplier<T> supplier, T fallback, String url) {
        // 组合装饰器：Retry 包裹 CircuitBreaker 包裹 Supplier
        Supplier<T> decoratedSupplier = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, supplier));

        try {
            return decoratedSupplier.get();
        } catch (CallNotPermittedException e) {
            // 熔断器开启，直接降级
            log.warn("[Python服务降级] 熔断器已开启，URL: {}，返回降级结果", url);
            return fallback;
        } catch (WebClientResponseException e) {
            // HTTP 错误响应
            log.warn("[Python服务降级] HTTP错误 {}，URL: {}，返回降级结果",
                    e.getStatusCode(), url);
            return fallback;
        } catch (Exception e) {
            // 其他异常（超时、连接失败等）
            log.warn("[Python服务降级] 调用失败，URL: {}，原因: {}，返回降级结果",
                    url, e.getMessage());
            return fallback;
        }
    }

    /**
     * 创建异常检测降级结果
     *
     * <p>降级策略：使用简单阈值判断代替 AI 模型检测
     * - CPU > 90% → 异常
     * - 内存 > 85% → 异常
     * - 其他情况 → 正常
     *
     * @param metricData 指标数据
     * @return 降级的检测结果
     */
    private Map<String, Object> createAnomalyFallbackResult(Map<String, Object> metricData) {
        boolean isAnomaly = false;
        String reason = "降级模式：简单阈值判断";

        // 简单阈值判断逻辑
        Object cpuValue = metricData.get("cpu_usage");
        Object memValue = metricData.get("memory_usage");

        if (cpuValue instanceof Number && ((Number) cpuValue).doubleValue() > 90) {
            isAnomaly = true;
            reason = "降级模式：CPU使用率超过90%阈值";
        } else if (memValue instanceof Number && ((Number) memValue).doubleValue() > 85) {
            isAnomaly = true;
            reason = "降级模式：内存使用率超过85%阈值";
        }

        return Map.of(
                "is_anomaly", isAnomaly,
                "confidence", 0.5,  // 降级模式置信度低
                "reason", reason,
                "degraded", true,   // 标记为降级结果
                "method", "threshold_fallback"
        );
    }

    // ============================================================
    // 状态查询方法（供监控使用）
    // ============================================================

    /**
     * 获取熔断器当前状态
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState().name();
    }

    /**
     * 获取熔断器指标
     */
    public Map<String, Object> getCircuitBreakerMetrics() {
        var metrics = circuitBreaker.getMetrics();
        return Map.of(
                "failureRate", metrics.getFailureRate(),
                "numberOfSuccessfulCalls", metrics.getNumberOfSuccessfulCalls(),
                "numberOfFailedCalls", metrics.getNumberOfFailedCalls(),
                "numberOfNotPermittedCalls", metrics.getNumberOfNotPermittedCalls(),
                "state", circuitBreaker.getState().name()
        );
    }
}
