package com.netdata.ops.core.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================
 * 混合检索器 - RRF 融合
 * ============================================================
 * 
 * 功能：
 * - 整合向量检索和 BM25 检索结果
 * - 使用 RRF (Reciprocal Rank Fusion) 算法融合
 * - 返回综合排序结果
 *
 * RRF 公式：
 * RRF_score(d) = Σ (1 / (k + rank_i(d)))
 *
 * 其中：
 * - d: 文档
 * - k: 平滑参数（默认 60）
 * - rank_i(d): 文档 d 在第 i 个检索结果中的排名
 *
 * 为什么使用 RRF？
 * - 无需调参，鲁棒性好
 * - 考虑排名而非原始分数，消除分数尺度差异
 * - 简单高效
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetriever {
    
    private final MilvusVectorStore vectorStore;
    private final BM25Retriever bm25Retriever;
    private final EmbeddingService embeddingService;
    
    @Value("${rag.retrieval.vector-top-k:20}")
    private int vectorTopK;
    
    @Value("${rag.retrieval.bm25-top-k:20}")
    private int bm25TopK;
    
    @Value("${rag.retrieval.final-top-k:5}")
    private int finalTopK;
    
    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;
    
    /**
     * 混合检索
     *
     * @param query 查询文本
     * @return 融合后的检索结果
     */
    public List<RetrievalResult> retrieve(String query) {
        return retrieve(query, finalTopK);
    }
    
    /**
     * 混合检索
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 融合后的检索结果
     */
    public List<RetrievalResult> retrieve(String query, int topK) {
        log.info("混合检索: {}, topK={}", query, topK);
        
        long startTime = System.currentTimeMillis();
        
        // 1. 向量检索
        List<MilvusVectorStore.SearchResult> vectorResults = vectorSearch(query);
        log.debug("向量检索返回 {} 条结果", vectorResults.size());
        
        // 2. BM25 检索
        List<BM25Retriever.BM25Result> bm25Results = bm25Retriever.search(query, bm25TopK);
        log.debug("BM25 检索返回 {} 条结果", bm25Results.size());
        
        // 3. RRF 融合
        List<RetrievalResult> mergedResults = rrfFusion(vectorResults, bm25Results);
        
        // 4. 返回 Top-K
        List<RetrievalResult> finalResults = mergedResults.stream()
            .limit(topK)
            .collect(Collectors.toList());
        
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("混合检索完成，返回 {} 条结果，耗时 {}ms", finalResults.size(), elapsed);
        
        return finalResults;
    }
    
    /**
     * 向量检索
     *
     * @param query 查询文本
     * @return 向量检索结果
     */
    private List<MilvusVectorStore.SearchResult> vectorSearch(String query) {
        try {
            // 将查询文本转换为向量
            float[] queryVector = embeddingService.embed(query);
            
            // 向量搜索
            return vectorStore.search(queryVector, vectorTopK);
            
        } catch (Exception e) {
            log.error("向量检索失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * RRF 融合
     *
     * 算法步骤：
     * 1. 为每个文档计算 RRF 分数
     * 2. 按分数降序排序
     * 3. 返回融合结果
     *
     * @param vectorResults 向量检索结果
     * @param bm25Results BM25 检索结果
     * @return 融合结果
     */
    private List<RetrievalResult> rrfFusion(
            List<MilvusVectorStore.SearchResult> vectorResults,
            List<BM25Retriever.BM25Result> bm25Results) {
        
        // 文档 ID -> RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        
        // 文档 ID -> 文档内容
        Map<String, RetrievalResult> docMap = new HashMap<>();
        
        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            MilvusVectorStore.SearchResult result = vectorResults.get(i);
            String docId = String.valueOf(result.getId());
            int rank = i + 1;
            
            // RRF 分数：1 / (k + rank)
            double rrfScore = 1.0 / (rrfK + rank);
            rrfScores.merge(docId, rrfScore, Double::sum);
            
            // 保存文档信息
            docMap.putIfAbsent(docId, RetrievalResult.builder()
                .id(docId)
                .content(result.getContent())
                .source(result.getSource())
                .title(result.getTitle())
                .chunkIndex(result.getChunkIndex())
                .vectorScore(result.getScore())
                .build());
        }
        
        // 处理 BM25 检索结果
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Retriever.BM25Result result = bm25Results.get(i);
            String docId = result.getDocId();
            int rank = i + 1;
            
            double rrfScore = 1.0 / (rrfK + rank);
            rrfScores.merge(docId, rrfScore, Double::sum);
            
            // 更新或创建文档信息
            RetrievalResult existing = docMap.get(docId);
            if (existing != null) {
                existing.setBm25Score(result.getScore());
            } else {
                // 如果文档只在BM25结果中出现，也需要添加到docMap
                docMap.put(docId, RetrievalResult.builder()
                    .id(docId)
                    .content(result.getContent())
                    .source(result.getSource())
                    .title(result.getTitle())
                    .chunkIndex(result.getChunkIndex())
                    .bm25Score(result.getScore())
                    .build());
            }
        }
        
        // 按 RRF 分数排序，并设置最终相关度分数（使用向量相似度）
        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(entry -> {
                RetrievalResult result = docMap.get(entry.getKey());
                if (result != null) {
                    result.setRrfScore(entry.getValue());
                    // 使用向量相似度作为最终相关度分数（范围0-1）
                    result.setFinalScore(result.getVectorScore() != null ? result.getVectorScore().doubleValue() : entry.getValue());
                }
                return result;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * 检索结果
     */
    @lombok.Data
    @lombok.Builder
    public static class RetrievalResult {
        /**
         * 文档 ID
         */
        private String id;
        
        /**
         * 文档内容
         */
        private String content;
        
        /**
         * 文档来源
         */
        private String source;
        
        /**
         * 文档标题
         */
        private String title;
        
        /**
         * 切片索引
         */
        private Long chunkIndex;
        
        /**
         * 向量检索分数
         */
        private Float vectorScore;
        
        /**
         * BM25 检索分数
         */
        private Double bm25Score;
        
        /**
         * RRF 融合分数
         */
        private Double rrfScore;
        
        /**
         * 最终相似度分数（归一化后）
         */
        private Double finalScore;
    }
}
