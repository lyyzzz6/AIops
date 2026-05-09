package com.netdata.ops.core.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Value;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;

/**
 * ============================================================
 * Embedding 服务 - 文本向量化
 * ============================================================
 * 
 * 功能：
 * - 将文本转换为向量表示
 * - 支持批量处理
 * - 调用本地 BGE-M3 模型
 *
 * 为什么使用 BGE-M3？
 * - 中文效果好（针对中文优化）
 * - 支持 100+ 语言
 * - 输出维度 1024，信息密度高
 * - 开源，可私有化部署
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    
    @Value("${embedding.service.url:http://localhost:8002}")
    private String embeddingServiceUrl;
    
    @Value("${embedding.model:bge-m3}")
    private String modelName;
    
    @Value("${embedding.batch-size:32}")
    private int batchSize;
    
    @Value("${embedding.timeout:30}")
    private int timeoutSeconds;
    
    private WebClient webClient;
    
    /**
     * 初始化 WebClient
     */
    private WebClient getWebClient() {
        if (webClient == null) {
            webClient = WebClient.builder()
                .baseUrl(embeddingServiceUrl)
                .codecs(configurer -> configurer.defaultCodecs()
                    .maxInMemorySize(10 * 1024 * 1024))  // 10MB
                .build();
        }
        return webClient;
    }
    
    /**
     * 将单个文本转换为向量
     *
     * @param text 输入文本
     * @return 向量（1024 维 float 数组）
     */
    public float[] embed(String text) {
        log.debug("向量化文本: {}...", text.substring(0, Math.min(50, text.length())));
        
        EmbeddingRequest request = new EmbeddingRequest();
        request.setInput(List.of(text));
        request.setModel(modelName);
        
        EmbeddingResponse response = getWebClient().post()
            .uri("/v1/embeddings")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(EmbeddingResponse.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .block();
        
        if (response == null || response.getData() == null 
            || response.getData().isEmpty()) {
            throw new RuntimeException("Embedding 服务返回空结果");
        }
        
        return response.getData().get(0).getEmbedding();
    }
    
    /**
     * 批量将文本转换为向量
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        log.debug("批量向量化 {} 条文本", texts.size());
        
        List<float[]> allEmbeddings = new ArrayList<>();
        
        // 分批处理，避免内存溢出
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            
            EmbeddingRequest request = new EmbeddingRequest();
            request.setInput(batch);
            request.setModel(modelName);
            
            EmbeddingResponse response = getWebClient().post()
                .uri("/v1/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds * 2))
                .block();
            
            if (response != null && response.getData() != null) {
                for (EmbeddingData data : response.getData()) {
                    allEmbeddings.add(data.getEmbedding());
                }
            }
            
            log.debug("已处理 {}/{} 条", end, texts.size());
        }
        
        return allEmbeddings;
    }
    
    /**
     * 计算两个向量的余弦相似度
     *
     * 余弦相似度公式：
     * similarity = (A · B) / (||A|| * ||B||)
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 相似度 [0, 1]
     */
    public float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        
        float dotProduct = 0f;
        float norm1 = 0f;
        float norm2 = 0f;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
    
    // ========== 内部类：请求和响应 ==========
    
    @lombok.Data
    private static class EmbeddingRequest {
        private List<String> input;
        private String model;
    }
    
    @lombok.Data
    private static class EmbeddingResponse {
        private List<EmbeddingData> data;
        private String model;
        private Usage usage;
    }
    
    @lombok.Data
    private static class EmbeddingData {
        private float[] embedding;
        private int index;
    }
    
    @lombok.Data
    private static class Usage {
        private int promptTokens;
        private int totalTokens;
    }
}
