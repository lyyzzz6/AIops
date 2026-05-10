package com.netdata.ops.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * LLM 调用降级处理器
 *
 * <p>职责：
 * <ul>
 *   <li>主路径：调用 DeepSeek API（通过 RestTemplate 直接调用）</li>
 *   <li>降级路径：DeepSeek 失败时自动切换到 Ollama 本地模型</li>
 *   <li>集成 Resilience4j 重试、熔断、并发隔离</li>
 *   <li>记录降级事件日志并统计降级次数</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class LLMFallbackHandler {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // DeepSeek 配置
    @Value("${spring.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String deepseekApiKey;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String deepseekModel;

    // Ollama 配置
    @Value("${llm.fallback.base-url:http://localhost:11434/v1}")
    private String ollamaBaseUrl;

    @Value("${llm.fallback.model:qwen2.5:7b}")
    private String ollamaModel;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    /** 降级次数统计（用于监控告警） */
    private final AtomicLong fallbackCount = new AtomicLong(0);

    /** 总调用次数统计 */
    private final AtomicLong totalCallCount = new AtomicLong(0);

    public LLMFallbackHandler(
            @Qualifier("llmApiCircuitBreaker") CircuitBreaker circuitBreaker,
            @Qualifier("llmApiRetry") Retry retry,
            @Qualifier("llmApiBulkhead") Bulkhead bulkhead,
            ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;

        // 注册熔断器状态变更事件监听
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("[LLM熔断器] 状态变更: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * 带容错的 LLM 同步调用
     *
     * <p>调用链路：Bulkhead → Retry → CircuitBreaker → Primary → Fallback
     *
     * @param prompt 用户提示词
     * @return LLM 生成的响应文本
     */
    public String call(String prompt) {
        totalCallCount.incrementAndGet();

        // 组合装饰器：Bulkhead → CircuitBreaker → Retry
        Supplier<String> decoratedSupplier = Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        Retry.decorateSupplier(retry, () -> invokePrimary(prompt))));

        try {
            return decoratedSupplier.get();
        } catch (CallNotPermittedException e) {
            // 熔断器开启，直接走降级
            log.warn("[LLM降级] 熔断器开启，直接使用本地模型。prompt长度: {}", prompt.length());
            return fallbackCall(prompt, e);
        } catch (Exception e) {
            // 重试耗尽或其他异常，走降级
            log.warn("[LLM降级] 主路径调用失败，切换本地模型。原因: {}", e.getMessage());
            return fallbackCall(prompt, e);
        }
    }

    /**
     * 带容错的 LLM 流式调用
     *
     * <p>流式场景下通过 Reactor 操作符集成 Resilience4j
     *
     * @param prompt 用户提示词
     * @return 流式响应文本
     */
    public Flux<String> stream(String prompt) {
        totalCallCount.incrementAndGet();

        return Flux.defer(() -> invokeStreamPrimary(prompt))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .onErrorResume(throwable -> {
                    log.warn("[LLM流式降级] 主路径失败，切换本地模型。原因: {}",
                            throwable.getMessage());
                    return fallbackStream(prompt, throwable);
                });
    }

    /**
     * 调用主 LLM（DeepSeek API）- 使用 RestTemplate 直接调用
     */
    private String invokePrimary(String prompt) {
        log.info("[LLM调用] 使用 DeepSeek API，URL: {}, Model: {}", deepseekBaseUrl, deepseekModel);
        log.debug("[LLM调用] Prompt长度: {}", prompt.length());
        
        try {
            // 构建请求体
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", deepseekModel);
            requestBody.put("messages", Collections.singletonList(message));
            requestBody.put("max_tokens", 1000);
            requestBody.put("temperature", 0.7);
            
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + deepseekApiKey);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            
            // 发送请求
            String url = deepseekBaseUrl + "/chat/completions";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            
            log.debug("[LLM调用] DeepSeek API响应状态: {}", response.getStatusCode());
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // 解析响应
                try {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    String content = root.path("choices").get(0).path("message").path("content").asText();
                    log.debug("[LLM调用] DeepSeek API调用成功，响应长度: {}", content.length());
                    return content;
                } catch (Exception e) {
                    log.error("[LLM调用] 解析响应失败: {}", e.getMessage(), e);
                    throw new RuntimeException("解析响应失败", e);
                }
            } else {
                throw new RuntimeException("DeepSeek API调用失败，状态码: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[LLM调用] DeepSeek API调用失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 流式调用主 LLM（DeepSeek API）
     */
    private Flux<String> invokeStreamPrimary(String prompt) {
        // 暂不实现流式调用，使用同步调用替代
        return Flux.just(invokePrimary(prompt));
    }

    /**
     * 真·同步阻塞式流式调用 DeepSeek（OpenAI 兼容 SSE）
     *
     * <p>直接在当前线程持续读取 HTTP 响应流，每收到一个 delta 就回调 {@code onDelta}。
     * 调用结束（收到 [DONE] 或流自然结束）后返回完整拼接文本。调用方捕获异常自行降级。
     *
     * @param prompt  提示词
     * @param onDelta 每段 delta 文本回调（不会传入 null，可能为空串时会跳过）
     * @return 完整拼接文本
     */
    public String streamSync(String prompt, Consumer<String> onDelta) {
        totalCallCount.incrementAndGet();
        try {
            return streamPrimarySync(prompt, onDelta);
        } catch (Exception e) {
            fallbackCount.incrementAndGet();
            log.warn("[LLM流式] 主路径失败，切换 Ollama 本地模型。原因: {}", e.getMessage());
            try {
                return streamFallbackSync(prompt, onDelta);
            } catch (Exception ex) {
                log.error("[LLM流式] 本地模型也失败: {}", ex.getMessage(), ex);
                String tip = "抱歉，AI 服务暂时不可用，请稍后重试。";
                if (onDelta != null) onDelta.accept(tip);
                return tip;
            }
        }
    }

    /**
     * DeepSeek 真流式（SSE）
     */
    private String streamPrimarySync(String prompt, Consumer<String> onDelta) {
        log.info("[LLM流式] 调用 DeepSeek SSE，URL: {}, Model: {}", deepseekBaseUrl, deepseekModel);
        String url = deepseekBaseUrl + "/chat/completions";
        return executeSseRequest(url, deepseekApiKey, deepseekModel, prompt, onDelta);
    }

    /**
     * Ollama 真流式（OpenAI 兼容 SSE）
     */
    private String streamFallbackSync(String prompt, Consumer<String> onDelta) {
        log.info("[LLM流式] 调用 Ollama SSE，URL: {}, Model: {}", ollamaBaseUrl, ollamaModel);
        String url = ollamaBaseUrl + "/chat/completions";
        return executeSseRequest(url, "ollama", ollamaModel, prompt, onDelta);
    }

    /**
     * 通用 OpenAI 兼容 SSE 流式执行：通过 RestTemplate.execute 读取原始 InputStream，
     * 逐行解析 {@code data: {\"choices\":[{\"delta\":{\"content\":\"...\"}}]} }。
     */
    private String executeSseRequest(String url, String apiKey, String model, String prompt, Consumer<String> onDelta) {
        final Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        final Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", Collections.singletonList(message));
        requestBody.put("max_tokens", 1000);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);

        final String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("构建 SSE 请求体失败", e);
        }

        RequestCallback requestCallback = req -> {
            req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            req.getHeaders().set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
            if (apiKey != null && !apiKey.isEmpty()) {
                req.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            req.getBody().write(bodyJson.getBytes(StandardCharsets.UTF_8));
        };

        ResponseExtractor<String> responseExtractor = (ClientHttpResponse response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("LLM SSE 请求失败，状态码: " + response.getStatusCode());
            }
            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonNode root = objectMapper.readTree(data);
                        JsonNode choices = root.path("choices");
                        if (!choices.isArray() || choices.size() == 0) continue;
                        JsonNode delta = choices.get(0).path("delta");
                        String piece = delta.path("content").asText("");
                        if (piece == null || piece.isEmpty()) continue;
                        full.append(piece);
                        if (onDelta != null) {
                            try { onDelta.accept(piece); } catch (Exception ex) {
                                log.warn("[LLM流式] onDelta 回调异常: {}", ex.getMessage());
                            }
                        }
                    } catch (Exception parseEx) {
                        log.debug("[LLM流式] 解析 SSE 行失败，已忽略。line={}, err={}", data, parseEx.getMessage());
                    }
                }
            }
            return full.toString();
        };

        return restTemplate.execute(url, HttpMethod.POST, requestCallback, responseExtractor);
    }

    /**
     * 降级调用 Ollama 本地模型
     */
    private String fallbackCall(String prompt, Exception e) {
        fallbackCount.incrementAndGet();
        log.warn("[LLM降级] 开始降级调用，累计降级次数: {}", fallbackCount.get());

        try {
            return invokeFallback(prompt);
        } catch (Exception ex) {
            log.error("[LLM降级] 本地模型也调用失败: {}", ex.getMessage(), ex);
            // 双重降级，返回兜底响应
            return "抱歉，AI 服务暂时不可用，请稍后重试。如需紧急帮助，请联系运维人员。";
        }
    }

    /**
     * 降级流式调用 Ollama 本地模型
     */
    private Flux<String> fallbackStream(String prompt, Throwable e) {
        fallbackCount.incrementAndGet();
        log.warn("[LLM流式降级] 开始降级调用，累计降级次数: {}", fallbackCount.get());
        return invokeStreamFallback(prompt);
    }

    /**
     * 调用降级 LLM（Ollama 本地模型）- 使用 RestTemplate 直接调用
     */
    private String invokeFallback(String prompt) {
        log.info("[LLM降级] 使用 Ollama 本地模型，URL: {}, Model: {}", ollamaBaseUrl, ollamaModel);
        
        try {
            // 构建请求体
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", ollamaModel);
            requestBody.put("messages", Collections.singletonList(message));
            requestBody.put("max_tokens", 1000);
            requestBody.put("temperature", 0.7);
            
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer ollama");
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            
            // 发送请求
            String url = ollamaBaseUrl + "/chat/completions";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // 解析响应
                try {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    String content = root.path("choices").get(0).path("message").path("content").asText();
                    log.debug("[LLM降级] Ollama调用成功，响应长度: {}", content.length());
                    return content;
                } catch (Exception e) {
                    log.error("[LLM降级] 解析响应失败: {}", e.getMessage(), e);
                    throw new RuntimeException("解析响应失败", e);
                }
            } else {
                throw new RuntimeException("Ollama调用失败，状态码: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[LLM降级] Ollama调用失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 流式调用降级 LLM（Ollama 本地模型）
     */
    private Flux<String> invokeStreamFallback(String prompt) {
        // 暂不实现流式调用
        return Flux.just(invokeFallback(prompt));
    }
}
