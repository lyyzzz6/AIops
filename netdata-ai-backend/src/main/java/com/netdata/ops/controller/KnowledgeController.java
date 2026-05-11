package com.netdata.ops.controller;

import com.netdata.ops.annotation.RequirePermission;
import com.netdata.ops.core.rag.HybridRetriever;
import com.netdata.ops.core.rag.RAGService;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.KnowledgeDocument;
import com.netdata.ops.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理控制器
 */
@Tag(name = "知识库管理", description = "文档CRUD、分类管理、搜索")
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final RAGService ragService;

    @Operation(summary = "分页查询文档列表")
    @GetMapping("/documents")
    @RequirePermission("knowledge:read")
    public R<PageResult<KnowledgeDocument>> listDocuments(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return R.ok(knowledgeService.getDocumentPage(current, size, category, status, keyword));
    }

    @Operation(summary = "获取文档详情")
    @GetMapping("/documents/{id}")
    @RequirePermission("knowledge:read")
    public R<KnowledgeDocument> getDocument(@PathVariable Long id) {
        return R.ok(knowledgeService.getDocumentById(id));
    }

    @Operation(summary = "上传/创建文档")
    @PostMapping("/documents")
    @RequirePermission("knowledge:write")
    public R<KnowledgeDocument> createDocument(@RequestBody Map<String, String> body) {
        KnowledgeDocument doc = knowledgeService.createDocument(
                body.get("title"),
                body.get("source"),
                body.get("contentType"),
                body.get("category"),
                body.get("content")
        );
        return R.ok("文档创建成功", doc);
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/documents/{id}")
    @RequirePermission("knowledge:delete")
    public R<Void> deleteDocument(@PathVariable Long id) {
        knowledgeService.deleteDocument(id);
        return R.ok();
    }

    @Operation(summary = "获取分类列表")
    @GetMapping("/categories")
    @RequirePermission("knowledge:read")
    public R<List<Map<String, Object>>> getCategories() {
        return R.ok(knowledgeService.getCategories());
    }

    @Operation(summary = "知识库统计")
    @GetMapping("/stats")
    @RequirePermission("knowledge:read")
    public R<Map<String, Object>> getStats() {
        return R.ok(knowledgeService.getStats());
    }

    @Operation(summary = "检索知识")
    @PostMapping("/retrieve")
    @RequirePermission("knowledge:read")
    public R<List<HybridRetriever.RetrievalResult>> retrieve(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        Integer topK = body.get("topK") != null ? (Integer) body.get("topK") : 5;
        return R.ok(ragService.retrieve(query, topK));
    }
}
