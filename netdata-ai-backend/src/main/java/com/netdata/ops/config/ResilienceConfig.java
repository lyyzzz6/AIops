package com.netdata.ops.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 容错框架编程式配置
 *
 * <p>为系统中所有外部调用提供统一的弹性策略，包括：
 * <ul>
 *   <li>anomalyDetection - Python 异常检测服务调用（重试3次 + 熔断 + 10s超时）</li>
 *   <li>llmApi - LLM API 调用（重试2次 + 熔断 + 15s超时 + 并发限流10）</li>
 *   <li>vectorSearch - Milvus 向量检索（重试2次 + 熔断 + 5s超时）</li>
 * </ul>
 *
 * <p>设计理由：
 * <ol>
 *   <li>使用编程式配置而非 YAML，方便其他 Agent 模块复用和动态调整</li>
 *   <li>通过 Registry 统一管理实例，支持运行时查询状态和指标</li>
 *   <li>不同服务采用差异化策略：LLM 超时更长（推理慢）、向量检索超时更短（应快速响应）</li>
 * </ol>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Configuration
public class ResilienceConfig {

    // ============================================================
    // 实例名称常量，供其他组件引用
    // ============================================================
    
    /** Python 异常检测服务实例名 */
    public static final String ANOMALY_DETECTION = "anomalyDetection";
    
    /** LLM API 调用实例名 */
    public static final String LLM_API = "llmApi";
    
    /** Milvus 向量检索实例名 */
    public static final String VECTOR_SEARCH = "vectorSearch";

    // ============================================================
    // 重试配置（Retry）
    // ============================================================

    /**
     * 重试策略注册中心
     *
     * <p>各服务重试策略说明：
     * - anomalyDetection: 3次重试，指数退避（100ms起，2倍递增，最大2s）
     * - llmApi: 2次重试，指数退避（500ms起，2倍递增）
     * - vectorSearch: 2次重试，固定间隔200ms
     */
    @Bean
    public RetryRegistry retryRegistry() {
        // Python 异常检测服务：网络抖动常见，多重试一次
        RetryConfig anomalyRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(100L, 2.0))
                .build();

        // LLM API：调用成本高，重试次数少但间隔长
        RetryConfig llmRetryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(500L, 2.0))
                .build();

        // Milvus 向量检索：固定间隔快速重试
        RetryConfig vectorRetryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .build();

        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.retry(ANOMALY_DETECTION, anomalyRetryConfig);
        registry.retry(LLM_API, llmRetryConfig);
        registry.retry(VECTOR_SEARCH, vectorRetryConfig);

        return registry;
    }

    // ============================================================
    // 熔断器配置（CircuitBreaker）
    // ============================================================

    /**
     * 熔断器注册中心
     *
     * <p>熔断策略说明：
     * - anomalyDetection: 滑动窗口10次，失败率50%触发熔断，30s后半开
     * - llmApi: 滑动窗口5次，失败率60%触发熔断，60s后半开（LLM 恢复较慢）
     * - vectorSearch: 滑动窗口10次，失败率50%触发熔断，20s后半开
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        // Python 异常检测服务
        CircuitBreakerConfig anomalyCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        // LLM API：窗口小（调用频率低），容忍度稍高
        CircuitBreakerConfig llmCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(60)
                .slidingWindowSize(5)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        // Milvus 向量检索：快速检测、快速恢复
        CircuitBreakerConfig vectorCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker(ANOMALY_DETECTION, anomalyCbConfig);
        registry.circuitBreaker(LLM_API, llmCbConfig);
        registry.circuitBreaker(VECTOR_SEARCH, vectorCbConfig);

        return registry;
    }

    // ============================================================
    // 超时配置（TimeLimiter）
    // ============================================================

    /**
     * 超时限制注册中心
     *
     * <p>超时策略说明：
     * - anomalyDetection: 10s（Python 服务正常响应在5s内）
     * - llmApi: 15s（LLM 推理较慢，特别是复杂 prompt）
     * - vectorSearch: 5s（向量检索应快速响应）
     */
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig anomalyTimeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(10))
                .build();

        TimeLimiterConfig llmTimeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(15))
                .build();

        TimeLimiterConfig vectorTimeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();
        registry.timeLimiter(ANOMALY_DETECTION, anomalyTimeLimiterConfig);
        registry.timeLimiter(LLM_API, llmTimeLimiterConfig);
        registry.timeLimiter(VECTOR_SEARCH, vectorTimeLimiterConfig);

        return registry;
    }

    // ============================================================
    // 并发隔离配置（Bulkhead）
    // ============================================================

    /**
     * Bulkhead 注册中心
     *
     * <p>仅 LLM API 配置并发限制：
     * - maxConcurrentCalls=10：防止过多并发请求耗尽 LLM API 配额
     * - maxWaitDuration=5s：超过并发上限后最多等待5s
     */
    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig llmBulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ofSeconds(5))
                .build();

        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        registry.bulkhead(LLM_API, llmBulkheadConfig);

        return registry;
    }

    // ============================================================
    // 便捷获取方法（供不通过 DI 获取的场景使用）
    // ============================================================

    /**
     * 获取指定名称的重试实例
     */
    @Bean(name = "anomalyDetectionRetry")
    public Retry anomalyDetectionRetry(RetryRegistry retryRegistry) {
        return retryRegistry.retry(ANOMALY_DETECTION);
    }

    @Bean(name = "llmApiRetry")
    public Retry llmApiRetry(RetryRegistry retryRegistry) {
        return retryRegistry.retry(LLM_API);
    }

    @Bean(name = "vectorSearchRetry")
    public Retry vectorSearchRetry(RetryRegistry retryRegistry) {
        return retryRegistry.retry(VECTOR_SEARCH);
    }

    /**
     * 获取指定名称的熔断器实例
     */
    @Bean(name = "anomalyDetectionCircuitBreaker")
    public CircuitBreaker anomalyDetectionCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker(ANOMALY_DETECTION);
    }

    @Bean(name = "llmApiCircuitBreaker")
    public CircuitBreaker llmApiCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker(LLM_API);
    }

    @Bean(name = "vectorSearchCircuitBreaker")
    public CircuitBreaker vectorSearchCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker(VECTOR_SEARCH);
    }

    /**
     * 获取 LLM API Bulkhead 实例
     */
    @Bean(name = "llmApiBulkhead")
    public Bulkhead llmApiBulkhead(BulkheadRegistry bulkheadRegistry) {
        return bulkheadRegistry.bulkhead(LLM_API);
    }
}
