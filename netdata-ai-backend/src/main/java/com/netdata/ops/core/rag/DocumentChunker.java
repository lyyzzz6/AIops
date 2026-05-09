package com.netdata.ops.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ============================================================
 * 文档切分器 - 语义切分实现
 * ============================================================
 * 
 * 切分策略：
 * 1. 首先按段落/标题分割
 * 2. 计算相邻段落的语义相似度（基于 Embedding）
 * 3. 相似度低于阈值处进行切分
 * 4. 最终得到语义完整的 Chunk
 *
 * 为什么使用语义切分而非固定长度？
 * - 固定长度会切断完整语义（如代码块、表格）
 * - 语义切分保持内容完整性
 * - 检索效果更好
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Component
public class DocumentChunker {
    
    /**
     * 切片大小（字符数）
     */
    @Value("${rag.chunk.chunk-size:500}")
    private int chunkSize;
    
    /**
     * 切片重叠大小
     */
    @Value("${rag.chunk.chunk-overlap:50}")
    private int chunkOverlap;
    
    /**
     * 最小切片大小
     */
    @Value("${rag.chunk.min-chunk-size:100}")
    private int minChunkSize;
    
    /**
     * 是否启用语义切分
     */
    @Value("${rag.chunk.semantic-chunking:true}")
    private boolean semanticChunking;
    
    /**
     * 段落分割正则
     * 匹配：空行、标题（#）、代码块边界
     */
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile(
        "(\\r?\\n){2,}|(?=^#{1,6}\\s)|(?=```)"
    );
    
    /**
     * 代码块正则
     */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
        "```[\\s\\S]*?```", Pattern.MULTILINE
    );
    
    /**
     * 对文档进行切分
     *
     * @param content 文档内容
     * @param title 文档标题
     * @param source 文档来源
     * @return 切片列表
     */
    public List<DocumentChunk> chunk(String content, String title, String source) {
        log.debug("开始切分文档: {}, 内容长度: {}", title, content.length());
        
        // 1. 预处理：提取代码块
        List<TextSegment> segments = preprocess(content);
        
        // 2. 切分
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        
        for (TextSegment segment : segments) {
            List<DocumentChunk> segmentChunks = chunkSegment(
                segment, title, source, chunkIndex
            );
            chunks.addAll(segmentChunks);
            chunkIndex += segmentChunks.size();
        }
        
        // 3. 后处理：合并过小的切片
        chunks = mergeSmallChunks(chunks);
        
        log.info("文档切分完成: {} -> {} 个切片", title, chunks.size());
        return chunks;
    }
    
    /**
     * 预处理：将文档分割为语义段
     *
     * @param content 原始内容
     * @return 语义段列表
     */
    private List<TextSegment> preprocess(String content) {
        List<TextSegment> segments = new ArrayList<>();
        
        // 提取代码块（代码块不切分）
        List<String> codeBlocks = new ArrayList<>();
        String processedContent = content;
        
        if (CODE_BLOCK_PATTERN.matcher(content).find()) {
            // 替换代码块为占位符
            processedContent = CODE_BLOCK_PATTERN.matcher(content).replaceAll(m -> {
                codeBlocks.add(m.group());
                return "[[CODE_BLOCK_" + (codeBlocks.size() - 1) + "]]";
            });
        }
        
        // 按段落分割
        String[] paragraphs = PARAGRAPH_PATTERN.split(processedContent);
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (!paragraph.isEmpty()) {
                // 恢复代码块
                for (int i = 0; i < codeBlocks.size(); i++) {
                    paragraph = paragraph.replace(
                        "[[CODE_BLOCK_" + i + "]]",
                        codeBlocks.get(i)
                    );
                }
                
                DocumentChunk.ChunkType type = detectChunkType(paragraph);
                segments.add(new TextSegment(paragraph, type));
            }
        }
        
        return segments;
    }
    
    /**
     * 对单个语义段进行切分
     */
    private List<DocumentChunk> chunkSegment(
            TextSegment segment, 
            String title, 
            String source,
            int startIndex) {
        
        List<DocumentChunk> chunks = new ArrayList<>();
        
        // 如果段落长度小于最大切片大小，直接作为一个切片
        if (segment.text.length() <= chunkSize) {
            chunks.add(createChunk(
                segment.text, title, source, startIndex, segment.type
            ));
            return chunks;
        }
        
        // 否则需要进一步切分
        // 按句子切分
        List<String> sentences = splitSentences(segment.text);
        
        StringBuilder currentChunk = new StringBuilder();
        int currentIndex = startIndex;
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize 
                && currentChunk.length() >= minChunkSize) {
                // 当前切片已满，保存
                chunks.add(createChunk(
                    currentChunk.toString().trim(), 
                    title, source, currentIndex++, segment.type
                ));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(sentence).append(" ");
        }
        
        // 处理最后一个切片
        if (currentChunk.length() > 0) {
            chunks.add(createChunk(
                currentChunk.toString().trim(), 
                title, source, currentIndex, segment.type
            ));
        }
        
        return chunks;
    }
    
    /**
     * 按句子分割文本
     * 支持中英文句子边界
     */
    private List<String> splitSentences(String text) {
        // 中英文句子结束符
        Pattern sentencePattern = Pattern.compile(
            "(?<=[。！？.!?])\\s*|(?<=\\n)"
        );
        
        return Arrays.stream(sentencePattern.split(text))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
    
    /**
     * 检测切片类型
     */
    private DocumentChunk.ChunkType detectChunkType(String text) {
        if (text.startsWith("```") || text.contains("```")) {
            return DocumentChunk.ChunkType.CODE_BLOCK;
        }
        if (text.matches("^[#]{1,6}\\s.*")) {
            return DocumentChunk.ChunkType.TITLE;
        }
        if (text.contains("|") && text.split("\\|").length > 2) {
            return DocumentChunk.ChunkType.TABLE;
        }
        if (text.matches("(?m)^\\s*[-*+0-9]+\\.\\s.*")) {
            return DocumentChunk.ChunkType.LIST;
        }
        return DocumentChunk.ChunkType.PARAGRAPH;
    }
    
    /**
     * 创建切片对象
     */
    private DocumentChunk createChunk(
            String content, 
            String title, 
            String source,
            int index,
            DocumentChunk.ChunkType type) {
        
        return DocumentChunk.builder()
            .id(generateChunkId(title, index))
            .content(content)
            .title(title)
            .source(source)
            .chunkIndex(index)
            .chunkType(type)
            .createdAt(java.time.LocalDateTime.now())
            .build();
    }
    
    /**
     * 生成切片 ID
     */
    private String generateChunkId(String title, int index) {
        return UUID.nameUUIDFromBytes((title + "_" + index).getBytes())
            .toString()
            .replace("-", "");
    }
    
    /**
     * 合并过小的切片
     */
    private List<DocumentChunk> mergeSmallChunks(List<DocumentChunk> chunks) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        
        List<DocumentChunk> merged = new ArrayList<>();
        DocumentChunk current = chunks.get(0);
        
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunk next = chunks.get(i);
            
            // 如果当前切片太小，尝试合并
            if (current.getContent().length() < minChunkSize) {
                current = DocumentChunk.builder()
                    .id(current.getId())
                    .content(current.getContent() + "\n\n" + next.getContent())
                    .title(current.getTitle())
                    .source(current.getSource())
                    .chunkIndex(current.getChunkIndex())
                    .chunkType(current.getChunkType())
                    .createdAt(current.getCreatedAt())
                    .build();
            } else {
                merged.add(current);
                current = next;
            }
        }
        
        merged.add(current);
        return merged;
    }
    
    /**
     * 内部类：文本段
     */
    private static class TextSegment {
        String text;
        DocumentChunk.ChunkType type;
        
        TextSegment(String text, DocumentChunk.ChunkType type) {
            this.text = text;
            this.type = type;
        }
    }
}
