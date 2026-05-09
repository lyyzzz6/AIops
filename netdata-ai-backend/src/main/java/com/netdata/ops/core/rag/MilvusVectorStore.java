package com.netdata.ops.core.rag;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.*;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.common.ConsistencyLevel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * ============================================================
 * Milvus 向量数据库客户端
 * ============================================================
 * 
 * 功能：
 * - Collection 创建与管理
 * - 向量插入、搜索、删除
 * - 索引配置
 *
 * 关键设计决策：
 * - 向量维度 1024：BGE-M3 固定输出维度
 * - 相似度度量 COSINE：适合文本语义检索
 * - 索引类型 IVF_FLAT：平衡性能和准确率
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Repository
public class MilvusVectorStore {
    
    @Value("${milvus.host:localhost}")
    private String host;
    
    @Value("${milvus.port:19531}")
    private int port;
    
    @Value("${milvus.database:default}")
    private String database;
    
    @Value("${milvus.collection-name:ops_knowledge_base}")
    private String collectionName;
    
    @Value("${milvus.vector-dimension:1024}")
    private int vectorDimension;
    
    private MilvusClientV2 client;
    private volatile boolean connected = false;
    
    /**
     * 检查 Milvus 是否可用
     */
    public boolean isAvailable() {
        return connected && client != null;
    }
    
    /**
     * 初始化 Milvus 连接
     *
     * 为什么在 @PostConstruct 中初始化？
     * - 确保 Spring Bean 完全初始化后再建立连接
     * - 可以在此处检查 Collection 是否存在，不存在则创建
     *
     * 为什么连接失败不抛异常？
     * - Milvus 是可选依赖，不可用时系统其他功能仍应正常工作
     * - RAGService 调用前会检查 isAvailable()，降级为无知识库模式
     */
    @PostConstruct
    public void init() {
        try {
            log.info("初始化 Milvus 连接: {}:{}", host, port);
            
            ConnectConfig connectConfig = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(database)
                .connectTimeoutMs(10_000L)
                .build();
            
            this.client = new MilvusClientV2(connectConfig);
            
            // 检查并创建 Collection
            createCollectionIfNotExists();
            
            this.connected = true;
            log.info("Milvus 连接成功");
        } catch (Exception e) {
            log.warn("Milvus 连接失败 ({}:{}), RAG 功能将不可用: {}", host, port, e.getMessage());
            this.client = null;
            this.connected = false;
        }
    }
    
    /**
     * 关闭连接
     */
    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
            log.info("Milvus 连接已关闭");
        }
    }
    
    /**
     * 创建 Collection（如果不存在）
     *
     * Collection 结构设计：
     * - id: INT64 主键（自增）
     * - content: VARCHAR 文档内容
     * - embedding: FLOAT_VECTOR(1024) 向量
     * - source: VARCHAR 文档来源
     * - title: VARCHAR 文档标题
     * - chunk_index: INT64 切片索引
     */
    private void createCollectionIfNotExists() {
        try {
            // 检查 Collection 是否存在
            HasCollectionReq hasReq = HasCollectionReq.builder()
                .collectionName(collectionName)
                .build();
            
            Boolean exists = client.hasCollection(hasReq);
            
            if (exists) {
                log.info("Collection {} 已存在", collectionName);
                return;
            }
            
            log.info("创建 Collection: {}", collectionName);
            
            // 创建 Collection Schema
            CreateCollectionReq.CollectionSchema schema = 
                CreateCollectionReq.CollectionSchema.builder()
                    .build();
            
            // 添加字段
            schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(io.milvus.v2.common.DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .build());
            
            schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(8000)
                .build());
            
            // 向量字段：维度 1024（BGE-M3 固定）
            schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(io.milvus.v2.common.DataType.FloatVector)
                .dimension(vectorDimension)
                .build());
            
            schema.addField(AddFieldReq.builder()
                .fieldName("source")
                .dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(512)
                .build());
            
            schema.addField(AddFieldReq.builder()
                .fieldName("title")
                .dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(256)
                .build());
            
            schema.addField(AddFieldReq.builder()
                .fieldName("chunk_index")
                .dataType(io.milvus.v2.common.DataType.Int64)
                .build());
            
            // 创建索引参数
            IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", 128))
                .build();
            
            // 创建 Collection
            CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .build();
            
            client.createCollection(createReq);
            
            log.info("Collection {} 创建成功，向量维度: {}", collectionName, vectorDimension);
            
        } catch (Exception e) {
            log.error("创建 Collection 失败", e);
            throw new RuntimeException("创建 Milvus Collection 失败", e);
        }
    }
    
    /**
     * 插入向量
     *
     * @param chunks 切片列表（已包含 embedding）
     * @return 插入的行数
     */
    public long insert(List<DocumentChunk> chunks) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过插入操作");
            return 0L;
        }
        if (chunks == null || chunks.isEmpty()) {
            return 0L;
        }
        
        log.debug("插入 {} 个向量到 Milvus", chunks.size());
        
        // 使用 JsonObject 构建行数据
        Gson gson = new Gson();
        List<JsonObject> rows = new ArrayList<>();
        
        for (DocumentChunk chunk : chunks) {
            JsonObject row = new JsonObject();
            row.addProperty("content", chunk.getContent());
            row.add("embedding", gson.toJsonTree(toFloatList(chunk.getEmbedding())));
            row.addProperty("source", chunk.getSource());
            row.addProperty("title", chunk.getTitle());
            row.addProperty("chunk_index", chunk.getChunkIndex());
            rows.add(row);
        }
        
        // 构建插入请求
        InsertReq insertReq = InsertReq.builder()
            .collectionName(collectionName)
            .data(rows)
            .build();
        
        InsertResp insertResp = client.insert(insertReq);
        
        long insertCnt = insertResp.getInsertCnt();
        log.debug("插入完成，共 {} 条", insertCnt);
        
        return insertCnt;
    }
    
    /**
     * float[] 转 List<Float>，用于 Gson 序列化
     */
    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }
    
    /**
     * 向量相似度搜索
     *
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @return 搜索结果
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        return search(queryVector, topK, null);
    }
    
    /**
     * 向量相似度搜索（带过滤条件）
     *
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @param filter 过滤条件（如 "source == 'xxx'"）
     * @return 搜索结果
     */
    public List<SearchResult> search(float[] queryVector, int topK, String filter) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，返回空搜索结果");
            return Collections.emptyList();
        }
        log.debug("向量搜索: topK={}, filter={}", topK, filter);
        
        // 构建搜索请求
        var searchBuilder = SearchReq.builder()
            .collectionName(collectionName)
            .data(List.of(new FloatVec(queryVector)))
            .annsField("embedding")
            .topK(topK)
            .outputFields(List.of("content", "source", "title", "chunk_index"))
            .consistencyLevel(ConsistencyLevel.BOUNDED);
        
        if (filter != null && !filter.isEmpty()) {
            searchBuilder.filter(filter);
        }
        
        SearchResp searchResp = client.search(searchBuilder.build());
        
        // 解析结果
        List<SearchResult> results = new ArrayList<>();
        
        for (SearchResp.SearchResult result : searchResp.getSearchResults().get(0)) {
            results.add(SearchResult.builder()
                .id((Long) result.getId())
                .score((float) result.getScore())
                .content((String) result.getEntity().get("content"))
                .source((String) result.getEntity().get("source"))
                .title((String) result.getEntity().get("title"))
                .chunkIndex((Long) result.getEntity().get("chunk_index"))
                .build());
        }
        
        log.debug("搜索完成，返回 {} 条结果", results.size());
        return results;
    }
    
    /**
     * 删除向量（按 ID）
     *
     * @param ids ID 列表
     */
    public void deleteByIds(List<Long> ids) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过删除操作");
            return;
        }
        if (ids == null || ids.isEmpty()) {
            return;
        }
        
        log.debug("删除 {} 个向量", ids.size());
        
        DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(collectionName)
            .ids(new ArrayList<Object>(ids))
            .build();
        
        client.delete(deleteReq);
    }
    
    /**
     * 删除向量（按过滤条件）
     *
     * @param filter 过滤条件
     */
    public void deleteByFilter(String filter) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过删除操作");
            return;
        }
        log.debug("按条件删除向量: {}", filter);
        
        DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(collectionName)
            .filter(filter)
            .build();
        
        client.delete(deleteReq);
    }
    
    /**
     * 获取 Collection 统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getStats() {
        if (!isAvailable()) {
            return Map.of("collectionName", collectionName, "rowCount", 0, "available", false);
        }
        GetCollectionStatsReq statsReq = GetCollectionStatsReq.builder()
            .collectionName(collectionName)
            .build();
        
        GetCollectionStatsResp statsResp = client.getCollectionStats(statsReq);
        
        return Map.of(
            "collectionName", collectionName,
            "rowCount", statsResp.getNumOfEntities(),
            "available", true
        );
    }
    
    /**
     * 搜索结果内部类
     */
    @lombok.Data
    @lombok.Builder
    public static class SearchResult {
        private Long id;
        private float score;
        private String content;
        private String source;
        private String title;
        private Long chunkIndex;
    }
}
