package com.netdata.ops.core.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================
 * BM25 关键词检索器
 * ============================================================
 * 
 * 功能：
 * - 基于词频的关键词检索
 * - 与向量检索互补，解决专有名词、缩写等问题
 *
 * BM25 公式：
 * score(D, Q) = Σ IDF(qi) * (f(qi, D) * (k1 + 1)) / (f(qi, D) + k1 * (1 - b + b * |D|/avgdl))
 *
 * 参数说明：
 * - k1 = 1.5：词频饱和参数
 * - b = 0.75：文档长度归一化参数
 *
 * 为什么需要 BM25？
 * - 向量检索偏向语义相似
 * - 专有名词（如 K8s）可能检索不到
 * - BM25 精确匹配关键词
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BM25Retriever {
    
    @Value("${rag.retrieval.bm25-top-k:20}")
    private int topK;
    
    // BM25 参数
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    
    /**
     * 文档索引（倒排索引）
     * key: 词，value: 包含该词的文档列表
     */
    private final Map<String, List<DocumentPosting>> invertedIndex = new HashMap<>();
    
    /**
     * 文档长度统计
     */
    private final Map<String, Integer> docLengths = new HashMap<>();
    
    /**
     * 平均文档长度
     */
    private double avgDocLength = 0;
    
    /**
     * 文档总数
     */
    private int totalDocs = 0;
    
    /**
     * 文档索引项
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class DocumentPosting {
        private String docId;
        private int tf;  // 词频
    }
    
    /**
     * 索引文档
     *
     * @param docId 文档 ID
     * @param content 文档内容
     */
    public void indexDocument(String docId, String content) {
        log.debug("索引文档: {}", docId);
        
        // 分词（简化版：按空格和标点分割）
        List<String> tokens = tokenize(content);
        
        // 更新文档长度
        docLengths.put(docId, tokens.size());
        totalDocs++;
        
        // 计算词频
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }
        
        // 更新倒排索引
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            int tf = entry.getValue();
            
            invertedIndex.computeIfAbsent(term, k -> new ArrayList<>())
                .add(new DocumentPosting(docId, tf));
        }
        
        // 更新平均文档长度
        updateAvgDocLength();
    }
    
    /**
     * 批量索引文档
     *
     * @param documents 文档列表 (docId -> content)
     */
    public void indexDocuments(Map<String, String> documents) {
        log.info("批量索引 {} 个文档", documents.size());
        
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            indexDocument(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * BM25 搜索
     *
     * @param query 查询文本
     * @return 搜索结果（按分数降序）
     */
    public List<BM25Result> search(String query) {
        return search(query, topK);
    }
    
    /**
     * BM25 搜索
     *
     * @param query 查询文本
     * @param k 返回数量
     * @return 搜索结果
     */
    public List<BM25Result> search(String query, int k) {
        log.debug("BM25 搜索: {}", query);
        
        // 查询分词
        List<String> queryTerms = tokenize(query);
        
        // 计算每个文档的 BM25 分数
        Map<String, Double> scores = new HashMap<>();
        
        for (String term : queryTerms) {
            List<DocumentPosting> postings = invertedIndex.get(term);
            if (postings == null) continue;
            
            // 计算 IDF
            double idf = calculateIDF(postings.size());
            
            for (DocumentPosting posting : postings) {
                String docId = posting.getDocId();
                int tf = posting.getTf();
                int docLength = docLengths.getOrDefault(docId, 0);
                
                // BM25 分数计算
                double tfNorm = (tf * (K1 + 1)) / 
                    (tf + K1 * (1 - B + B * docLength / avgDocLength));
                
                scores.merge(docId, idf * tfNorm, Double::sum);
            }
        }
        
        // 排序并返回 Top-K
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(k)
            .map(e -> new BM25Result(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
    }
    
    /**
     * 计算 IDF（逆文档频率）
     *
     * IDF(q) = log((N - n(q) + 0.5) / (n(q) + 0.5) + 1)
     *
     * @param docFreq 包含该词的文档数
     * @return IDF 值
     */
    private double calculateIDF(int docFreq) {
        return Math.log((totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1);
    }
    
    /**
     * 分词
     *
     * 简化实现：按空格、标点分割，转小写
     * 生产环境应使用专业分词器（如 IK、Jieba）
     *
     * @param text 文本
     * @return 词列表
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 简化分词：按非字母数字分割
        return Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-z0-9\u4e00-\u9fa5]", " ")
                .split("\\s+"))
            .filter(s -> !s.isEmpty() && s.length() > 1)  // 过滤单字
            .collect(Collectors.toList());
    }
    
    /**
     * 更新平均文档长度
     */
    private void updateAvgDocLength() {
        if (totalDocs > 0) {
            avgDocLength = docLengths.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0);
        }
    }
    
    /**
     * 清空索引
     */
    public void clear() {
        invertedIndex.clear();
        docLengths.clear();
        totalDocs = 0;
        avgDocLength = 0;
    }
    
    /**
     * 获取索引统计
     */
    public Map<String, Object> getStats() {
        return Map.of(
            "totalDocs", totalDocs,
            "totalTerms", invertedIndex.size(),
            "avgDocLength", avgDocLength
        );
    }
    
    /**
     * BM25 搜索结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class BM25Result {
        private String docId;
        private double score;
    }
}
