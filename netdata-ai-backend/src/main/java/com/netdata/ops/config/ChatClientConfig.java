package com.netdata.ops.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatClient 多实例配置
 *
 * <p>配置两套 ChatClient 实例用于 LLM 降级：
 * <ul>
 *   <li>primaryChatClient - DeepSeek API（主路径，生产环境使用）</li>
 *   <li>fallbackChatClient - Ollama 本地模型（降级路径，兜底使用）</li>
 * </ul>
 *
 * <p>设计理由：
 * <ol>
 *   <li>分离主/备 ChatClient 实例，由 LLMFallbackHandler 负责切换</li>
 *   <li>Ollama 本地部署无网络依赖，作为降级方案可靠性更高</li>
 *   <li>两套实例独立配置，互不影响</li>
 * </ol>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Configuration
public class ChatClientConfig {

    // ============================================================
    // DeepSeek API 配置（主路径）
    // ============================================================

    @Value("${spring.ai.openai.api-key:sk-xxxxxxxx}")
    private String deepseekApiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String deepseekModel;

    // ============================================================
    // Ollama 本地配置（降级路径）
    // ============================================================

    @Value("${llm.fallback.base-url:http://localhost:11434/v1}")
    private String ollamaBaseUrl;

    @Value("${llm.fallback.model:qwen2.5:7b}")
    private String ollamaModel;

    /**
     * 主 ChatClient - DeepSeek API
     *
     * <p>生产环境使用，通过 Spring AI OpenAI 兼容接口调用 DeepSeek
     */
    @Bean(name = "primaryChatClient")
    @Primary
    public ChatClient primaryChatClient() {
        log.info("[ChatClient配置] 初始化主ChatClient - DeepSeek API");
        log.info("[ChatClient配置] API Key: {}...", deepseekApiKey.substring(0, Math.min(10, deepseekApiKey.length())));
        log.info("[ChatClient配置] Base URL: {}", deepseekBaseUrl);
        log.info("[ChatClient配置] Model: {}", deepseekModel);
        
        OpenAiApi openAiApi = new OpenAiApi(deepseekBaseUrl, deepseekApiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(deepseekModel)
                .withTemperature(0.7)
                .withMaxTokens(4096)
                .build();
        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options);
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 降级 ChatClient - Ollama 本地模型
     *
     * <p>当 DeepSeek 不可用时自动切换，使用本地 Ollama 服务
     * 注意：Ollama 兼容 OpenAI API 格式，因此可复用 OpenAI 客户端
     */
    @Bean(name = "fallbackChatClient")
    public ChatClient fallbackChatClient() {
        log.info("[ChatClient配置] 初始化降级ChatClient - Ollama");
        log.info("[ChatClient配置] Base URL: {}", ollamaBaseUrl);
        log.info("[ChatClient配置] Model: {}", ollamaModel);
        
        OpenAiApi openAiApi = new OpenAiApi(ollamaBaseUrl, "ollama");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(ollamaModel)
                .withTemperature(0.7)
                .withMaxTokens(2048)
                .build();
        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options);
        return ChatClient.builder(chatModel).build();
    }
}

