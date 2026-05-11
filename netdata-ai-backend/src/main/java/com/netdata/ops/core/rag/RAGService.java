package com.netdata.ops.core.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================
 * RAG 服务 - 检索增强生成
 * ============================================================
 * 
 * 功能：
 * - 文档入库：切分 -> 向量化 -> 存储
 * - 知识检索：混合检索 -> Reranker 精排
 * - 上下文构建：为 LLM 生成提供上下文
 *
 * RAG 流程：
 * 1. 用户提问
 * 2. 混合检索（向量 + BM25）
 * 3. RRF 融合
 * 4. Reranker 精排（可选）
 * 5. Top-K 注入 Prompt
 * 6. LLM 生成答案
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {
    
    private final DocumentChunker documentChunker;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStore vectorStore;
    private final BM25Retriever bm25Retriever;
    private final HybridRetriever hybridRetriever;
    
    /**
     * 文档入库
     *
     * 流程：
     * 1. 文档切分
     * 2. 向量化
     * 3. 存储到 Milvus
     * 4. 更新 BM25 索引
     *
     * @param content 文档内容
     * @param title 文档标题
     * @param source 文档来源
     * @return 入库的切片数量
     */
    @Transactional
    public int ingestDocument(String content, String title, String source) {
        log.info("入库文档: {}", title);
        
        // 1. 切分文档
        List<DocumentChunk> chunks = documentChunker.chunk(content, title, source);
        log.debug("切分为 {} 个切片", chunks.size());
        
        // 2. 批量向量化
        List<String> texts = chunks.stream()
            .map(DocumentChunk::getContent)
            .collect(Collectors.toList());
        
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        
        // 3. 设置向量到切片
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(embeddings.get(i));
        }
        
        // 4. 存储到 Milvus
        long insertCnt = vectorStore.insert(chunks);
        log.debug("存储到 Milvus: {} 条", insertCnt);
        
        // 5. 更新 BM25 索引（使用切片索引作为文档ID）
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            String docId = source + "#" + i;
            Long chunkIndex = chunk.getChunkIndex() != null ? chunk.getChunkIndex().longValue() : null;
            bm25Retriever.indexDocument(docId, chunk.getContent(), chunk.getSource(), chunk.getTitle(), chunkIndex);
        }
        
        log.info("文档入库完成: {} -> {} 个切片", title, chunks.size());
        return chunks.size();
    }
    
    /**
     * 批量入库文档
     *
     * @param documents 文档列表
     * @return 入库总切片数
     */
    public int ingestDocuments(List<DocumentInput> documents) {
        log.info("批量入库 {} 个文档", documents.size());
        
        int totalChunks = 0;
        for (DocumentInput doc : documents) {
            totalChunks += ingestDocument(doc.getContent(), doc.getTitle(), doc.getSource());
        }
        
        return totalChunks;
    }
    
    /**
     * 知识检索
     *
     * @param query 查询文本
     * @return 检索结果
     */
    public List<HybridRetriever.RetrievalResult> retrieve(String query) {
        return retrieve(query, 5);
    }
    
    /**
     * 知识检索
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 检索结果
     */
    public List<HybridRetriever.RetrievalResult> retrieve(String query, int topK) {
        log.info("知识检索: {}", query);
        return hybridRetriever.retrieve(query, topK);
    }
    
    /**
     * 构建 RAG 上下文
     *
     * 将检索结果格式化为 Prompt 上下文
     *
     * @param results 检索结果
     * @return 格式化的上下文文本
     */
    public String buildContext(List<HybridRetriever.RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        context.append("以下是相关知识片段，请参考这些信息回答问题：\n\n");
        
        for (int i = 0; i < results.size(); i++) {
            HybridRetriever.RetrievalResult result = results.get(i);
            
            context.append(String.format("[来源 %d] %s\n", i + 1, result.getSource()));
            context.append(String.format("标题: %s\n", result.getTitle()));
            context.append(String.format("内容: %s\n\n", result.getContent()));
        }
        
        return context.toString();
    }
    
    /**
     * 生成引用来源
     *
     * @param results 检索结果
     * @return 引用来源列表
     */
    public List<Map<String, Object>> generateCitations(
            List<HybridRetriever.RetrievalResult> results) {
        
        return results.stream()
            .map(r -> Map.<String, Object>of(
                "source", r.getSource(),
                "title", r.getTitle(),
                "score", r.getRrfScore()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * 删除文档
     *
     * @param source 文档来源
     */
    public void deleteDocument(String source) {
        log.info("删除文档: {}", source);
        
        // 从 Milvus 删除
        vectorStore.deleteByFilter("source == \"" + source + "\"");
        
        // 注意：BM25 索引目前不支持按条件删除，需要重建索引
        // 生产环境应考虑增量更新机制
    }
    
    /**
     * 获取知识库统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("vectorStore", vectorStore.getStats());
        stats.put("bm25Index", bm25Retriever.getStats());
        return stats;
    }
    
    /**
     * 文档输入
     */
    @lombok.Data
    public static class DocumentInput {
        private String title;
        private String content;
        private String source;
    }
}
