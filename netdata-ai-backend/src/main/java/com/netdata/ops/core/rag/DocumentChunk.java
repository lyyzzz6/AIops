package com.netdata.ops.core.rag;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * 文档切片实体类
 * ============================================================
 * 
 * 设计说明：
 * - 一个文档被切分成多个 Chunk
 * - 每个 Chunk 包含原文内容 + 元数据
 * - Chunk 是向量化存储的基本单元
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {
    
    /**
     * 切片唯一标识
     */
    private String id;
    
    /**
     * 切片内容（文本）
     */
    private String content;
    
    /**
     * 向量表示（1024 维，BGE-M3）
     * 注意：向量维度创建后不可更改！
     */
    private float[] embedding;
    
    /**
     * 来源文档标题
     */
    private String title;
    
    /**
     * 来源文档路径或 URL
     */
    private String source;
    
    /**
     * 切片索引（同一文档中的第几个切片）
     */
    private Integer chunkIndex;
    
    /**
     * 切片类型
     */
    private ChunkType chunkType;
    
    /**
     * 元数据（扩展字段）
     */
    private Map<String, Object> metadata;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 切片类型枚举
     */
    public enum ChunkType {
        /**
         * 标题切片
         */
        TITLE,
        /**
         * 段落切片
         */
        PARAGRAPH,
        /**
         * 代码块切片
         */
        CODE_BLOCK,
        /**
         * 列表切片
         */
        LIST,
        /**
         * 表格切片
         */
        TABLE
    }
    
    /**
     * 获取向量维度的长度
     * @return 向量维度
     */
    public int getEmbeddingDimension() {
        return embedding != null ? embedding.length : 0;
    }
    
    /**
     * 验证切片有效性
     * @return 是否有效
     */
    public boolean isValid() {
        return content != null && !content.trim().isEmpty()
            && embedding != null && embedding.length == 1024;
    }
}
