package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.entity.ChatConversation;
import com.netdata.ops.entity.ChatMessage;
import com.netdata.ops.mapper.ChatConversationMapper;
import com.netdata.ops.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问答历史持久化服务
 *
 * 负责 chat_conversation 与 chat_message 两张表的读写。
 * 归属校验：所有查询/删除接口均要求 conversation.userId 与传入 userId 相等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据 sessionId 获取或创建会话。未登录用户（userId==null）不落盘。
     */
    @Transactional
    public ChatConversation getOrCreateConversation(String sessionId, Long userId, String firstQuery) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        ChatConversation existing = conversationMapper.selectOne(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getSessionId, sessionId)
                        .eq(ChatConversation::getUserId, userId)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            return existing;
        }

        ChatConversation conv = new ChatConversation();
        conv.setSessionId(sessionId);
        conv.setUserId(userId);
        conv.setTitle(buildTitle(firstQuery));
        conv.setMessageCount(0);
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv;
    }

    /**
     * 追加用户消息
     */
    @Transactional
    public void appendUserMessage(Long conversationId, String content) {
        if (conversationId == null) return;
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setRole("user");
        msg.setContent(content);
        msg.setTokens(0);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
        bumpConversation(conversationId, null, null);
    }

    /**
     * 追加助手消息（含 sources、suggestedCommands、intent、agentUsed、executionTimeMs）
     */
    @Transactional
    public void appendAssistantMessage(Long conversationId, String content, Object sources,
                                        Object suggestedCommands,
                                        String intent, String agentUsed, Long executionTimeMs) {
        if (conversationId == null) return;
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setRole("assistant");
        msg.setContent(content != null ? content : "");
        msg.setTokens(0);
        msg.setSources(toJson(sources));
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("intent", intent != null ? intent : "");
        meta.put("agentUsed", agentUsed != null ? agentUsed : "");
        meta.put("executionTimeMs", executionTimeMs != null ? executionTimeMs : 0L);
        if (suggestedCommands != null) {
            meta.put("suggestedCommands", suggestedCommands);
        }
        msg.setMetadata(toJson(meta));
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
        bumpConversation(conversationId, intent, agentUsed);
    }

    /**
     * 分页查询当前用户的会话列表（按 updatedAt 倒序）
     */
    public PageResult<ChatConversation> listConversations(int current, int size, Long userId) {
        if (userId == null) {
            return PageResult.of(List.of(), 0, current, size);
        }
        Page<ChatConversation> page = new Page<>(current, size);
        IPage<ChatConversation> result = conversationMapper.selectPage(page,
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getUserId, userId)
                        .orderByDesc(ChatConversation::getUpdatedAt));
        return PageResult.of(result.getRecords(), result.getTotal(), current, size);
    }

    /**
     * 获取会话下的所有消息（按 createdAt 升序）
     */
    public List<ChatMessage> getMessages(Long conversationId, Long userId) {
        ensureOwner(conversationId, userId);
        return messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getCreatedAt)
                        .orderByAsc(ChatMessage::getId));
    }

    /**
     * 删除会话（级联删除消息：DDL 已设外键 ON DELETE CASCADE）
     */
    @Transactional
    public void deleteConversation(Long conversationId, Long userId) {
        ensureOwner(conversationId, userId);
        // 先显式删除消息（避免外键约束不存在时残留）
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    /**
     * 清空会话消息但保留会话
     */
    @Transactional
    public void clearMessages(Long conversationId, Long userId) {
        ensureOwner(conversationId, userId);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
        ChatConversation conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            conv.setMessageCount(0);
            conv.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conv);
        }
    }

    // ==================== 私有工具方法 ====================

    private void bumpConversation(Long conversationId, String intent, String agentUsed) {
        ChatConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) return;
        conv.setMessageCount((conv.getMessageCount() == null ? 0 : conv.getMessageCount()) + 1);
        if (intent != null && !intent.isBlank()) conv.setIntent(intent);
        if (agentUsed != null && !agentUsed.isBlank()) conv.setAgentUsed(agentUsed);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    private void ensureOwner(Long conversationId, Long userId) {
        if (conversationId == null || userId == null) {
            throw new IllegalArgumentException("conversationId 或 userId 不能为空");
        }
        ChatConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
    }

    private String buildTitle(String firstQuery) {
        if (firstQuery == null || firstQuery.isBlank()) return "新对话";
        String trimmed = firstQuery.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) + "..." : trimmed;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
