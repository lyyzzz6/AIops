package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netdata.ops.core.rag.RAGService;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.entity.KnowledgeDocument;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.KnowledgeDocumentMapper;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocumentMapper documentMapper;
    private final RAGService ragService;

    /**
     * 分页查询文档列表
     */
    public PageResult<KnowledgeDocument> getDocumentPage(int current, int size,
                                                          String category, Integer status, String keyword) {
        Page<KnowledgeDocument> page = new Page<>(current, size);
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();

        if (category != null && !category.isBlank()) {
            wrapper.eq(KnowledgeDocument::getCategory, category);
        }
        if (status != null) {
            wrapper.eq(KnowledgeDocument::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(KnowledgeDocument::getTitle, keyword);
        }
        wrapper.orderByDesc(KnowledgeDocument::getCreatedAt);

        Page<KnowledgeDocument> result = documentMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 获取文档详情
     */
    public KnowledgeDocument getDocumentById(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "文档不存在");
        }
        return doc;
    }

    /**
     * 创建文档记录并向量化入库
     */
    @Transactional
    public KnowledgeDocument createDocument(String title, String source, String contentType,
                                            String category, String content) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle(title);
        doc.setSource(source);
        doc.setContentType(contentType != null ? contentType : "markdown");
        doc.setCategory(category);
        doc.setContent(content);
        doc.setWordCount(content != null ? content.length() : 0);
        doc.setStatus(0); // 处理中
        doc.setCreatedBy(SecurityUtils.getCurrentUserId());
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        // 调用RAGService进行向量化并写入Milvus
        try {
            int chunkCount = ragService.ingestDocument(content, title, source);
            doc.setChunkCount(chunkCount);
            doc.setStatus(1); // 完成
            log.info("文档向量化完成: title={}, chunks={}", title, chunkCount);
        } catch (Exception e) {
            log.error("文档向量化失败: title={}, error={}", title, e.getMessage());
            doc.setStatus(2); // 失败
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文档向量化失败: " + e.getMessage());
        }
        
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(doc);

        log.info("知识库文档创建: title={} by {}", title, SecurityUtils.getCurrentUsername());
        return doc;
    }

    /**
     * 删除文档（同时清理Milvus向量）
     */
    @Transactional
    public void deleteDocument(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "文档不存在");
        }

        // 调用RAGService清理Milvus向量
        try {
            ragService.deleteDocument(doc.getSource());
            log.info("Milvus向量清理完成: source={}", doc.getSource());
        } catch (Exception e) {
            log.warn("Milvus向量清理失败: source={}, error={}", doc.getSource(), e.getMessage());
            // 继续删除数据库记录，不中断流程
        }
        
        documentMapper.deleteById(id);
        log.info("知识库文档删除: id={} by {}", id, SecurityUtils.getCurrentUsername());
    }

    /**
     * 获取分类列表
     */
    public List<Map<String, Object>> getCategories() {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(KnowledgeDocument::getCategory);
        wrapper.groupBy(KnowledgeDocument::getCategory);
        wrapper.isNotNull(KnowledgeDocument::getCategory);

        return documentMapper.selectList(wrapper).stream()
                .map(doc -> Map.<String, Object>of(
                        "category", doc.getCategory() != null ? doc.getCategory() : "未分类",
                        "count", countByCategory(doc.getCategory())
                ))
                .collect(Collectors.toList());
    }

    /**
     * 知识库统计
     */
    public Map<String, Object> getStats() {
        long total = documentMapper.selectCount(null);
        LambdaQueryWrapper<KnowledgeDocument> completedWrapper = new LambdaQueryWrapper<>();
        completedWrapper.eq(KnowledgeDocument::getStatus, 1);
        long completed = documentMapper.selectCount(completedWrapper);

        return Map.of(
                "totalDocuments", total,
                "completedDocuments", completed,
                "processingDocuments", total - completed
        );
    }

    private long countByCategory(String category) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getCategory, category);
        return documentMapper.selectCount(wrapper);
    }
}
