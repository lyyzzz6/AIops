package com.netdata.ops.core.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.core.agent.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * 混合意图分类器（组合策略）
 *
 * <p>双级分类架构的核心实现，按以下优先级进行意图分类：
 * <ol>
 *   <li>Redis 缓存查找：避免重复分类，提升响应速度</li>
 *   <li>规则快速路径：高置信度（> 0.9）时直接使用，跳过 LLM</li>
 *   <li>LLM 语义分类：规则无法确定时，使用 LLM 进行深层语义理解</li>
 * </ol>
 *
 * <p>设计理由：
 * <ul>
 *   <li>规则分类速度快（< 1ms），适合明确意图的快速响应</li>
 *   <li>LLM 分类精度高，适合模糊意图和复合请求</li>
 *   <li>缓存层减少 LLM 调用次数，降低成本和延迟</li>
 *   <li>@Primary 确保自动注入时选择此实现</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Primary
@Slf4j
public class HybridIntentClassifier implements IntentClassifier {

    private final RuleBasedClassifier ruleClassifier;
    private final LLMIntentClassifier llmClassifier;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Redis 缓存 key 前缀
     */
    private static final String CACHE_PREFIX = "agent:intent:";

    /**
     * 缓存过期时间：5 分钟
     * 为什么 5 分钟：意图分类结果具有时效性，过长可能导致上下文变化后命中旧结果
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * 规则快速路径的置信度阈值
     * 高于此值的规则分类结果直接使用，不再调用 LLM
     */
    private static final double RULE_FAST_PATH_THRESHOLD = 0.9;

    public HybridIntentClassifier(RuleBasedClassifier ruleClassifier,
                                  LLMIntentClassifier llmClassifier,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper) {
        this.ruleClassifier = ruleClassifier;
        this.llmClassifier = llmClassifier;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntentResult classify(String query, List<AgentContext.ChatMessage> history) {
        log.info("[混合分类] 开始意图分类: {}", query);

        // === Step 1: 缓存查找 ===
        IntentResult cached = lookupCache(query);
        if (cached != null) {
            log.info("[混合分类] 缓存命中: intent={}, confidence={}",
                    cached.getIntentType(), cached.getConfidence());
            return cached;
        }

        // === Step 2: 规则快速路径 ===
        IntentResult ruleResult = ruleClassifier.classify(query, history);
        log.info("[混合分类] 规则分类结果: intent={}, confidence={}",
                ruleResult.getIntentType(), ruleResult.getConfidence());

        if (ruleResult.getConfidence() >= RULE_FAST_PATH_THRESHOLD) {
            // 高置信度，直接使用规则结果
            log.info("[混合分类] 规则高置信度命中，跳过 LLM");
            writeCache(query, ruleResult);
            return ruleResult;
        }

        // === Step 3: LLM 语义分类 ===
        log.info("[混合分类] 规则置信度不足({})，调用 LLM 语义分类",
                ruleResult.getConfidence());
        IntentResult llmResult = llmClassifier.classify(query, history);
        log.info("[混合分类] LLM 分类结果: intent={}, confidence={}",
                llmResult.getIntentType(), llmResult.getConfidence());

        // === Step 4: 写入缓存 ===
        writeCache(query, llmResult);

        return llmResult;
    }

    /**
     * 从 Redis 缓存中查找意图分类结果
     *
     * @param query 用户查询
     * @return 缓存的分类结果，未命中返回 null
     */
    private IntentResult lookupCache(String query) {
        try {
            String cacheKey = buildCacheKey(query);
            String cached = redisTemplate.opsForValue().get(cacheKey);

            if (cached != null && !cached.isBlank()) {
                IntentResult result = objectMapper.readValue(cached, IntentResult.class);
                result.setFromCache(true);
                result.setClassifierSource("cache");
                return result;
            }
        } catch (Exception e) {
            // 缓存异常不影响主流程
            log.warn("[混合分类] 缓存查询异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将分类结果写入 Redis 缓存
     *
     * @param query  用户查询
     * @param result 分类结果
     */
    private void writeCache(String query, IntentResult result) {
        try {
            String cacheKey = buildCacheKey(query);
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
            log.debug("[混合分类] 分类结果已缓存: key={}", cacheKey);
        } catch (Exception e) {
            // 缓存写入失败不影响主流程
            log.warn("[混合分类] 缓存写入异常: {}", e.getMessage());
        }
    }

    /**
     * 构建缓存 key
     * 使用 MD5(query) 作为唯一标识，避免 key 过长
     *
     * @param query 用户查询
     * @return Redis key
     */
    private String buildCacheKey(String query) {
        return CACHE_PREFIX + md5(query);
    }

    /**
     * 计算字符串的 MD5 哈希值
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 在所有 JVM 中都可用，理论上不会抛出此异常
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }
}
