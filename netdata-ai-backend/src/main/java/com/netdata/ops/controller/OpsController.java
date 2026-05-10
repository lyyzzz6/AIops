package com.netdata.ops.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.core.agent.*;
import com.netdata.ops.core.ai.LLMFallbackHandler;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.entity.ChatConversation;
import com.netdata.ops.entity.ChatMessage;
import com.netdata.ops.service.ChatHistoryService;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================
 * 智能运维 API 控制器
 * ============================================================
 *
 * API 端点：
 * - POST   /chat                                 智能问答（自动落盘）
 * - GET    /chat/conversations                   分页查询我的会话
 * - GET    /chat/conversations/{id}/messages     获取某会话的消息列表
 * - DELETE /chat/conversations/{id}              删除会话
 * - DELETE /chat/conversations/{id}/messages     清空会话消息
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OpsController {

    private final OrchestratorAgent orchestratorAgent;
    private final ChatHistoryService chatHistoryService;
    private final LLMFallbackHandler llmFallbackHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-stream-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });

    /**
     * 智能问答（自动落盘 chat_conversation + chat_message）
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        log.info("收到问答请求: {}", request.getQuery());

        Long currentUserId = SecurityUtils.getCurrentUserId();
        String userIdStr = currentUserId != null ? String.valueOf(currentUserId) : request.getUserId();

        // 构建上下文
        AgentContext context = AgentContext.builder()
                .sessionId(request.getSessionId())
                .userId(userIdStr)
                .query(request.getQuery())
                .build();

        // 确保会话存在（未登录时返回 null，不落盘）
        ChatConversation conversation = chatHistoryService.getOrCreateConversation(
                request.getSessionId(), currentUserId, request.getQuery());

        // 落盘用户消息
        if (conversation != null) {
            chatHistoryService.appendUserMessage(conversation.getId(), request.getQuery());
        }

        // 执行 Agent
        AgentResult result = orchestratorAgent.execute(context);

        // 落盘助手消息
        if (conversation != null) {
            String intent = result.getIntentType() != null ? result.getIntentType().name() : null;
            chatHistoryService.appendAssistantMessage(
                    conversation.getId(),
                    result.getResponse(),
                    result.getSources(),
                    result.getSuggestedCommands(),
                    intent,
                    result.getAgentName(),
                    result.getExecutionTimeMs()
            );
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("response", result.getResponse() != null ? result.getResponse() : "");
        response.put("intent", result.getIntentType() != null ? result.getIntentType().name() : "UNKNOWN");
        response.put("sources", result.getSources() != null ? result.getSources() : java.util.Collections.emptyList());
        response.put("suggestedCommands", result.getSuggestedCommands() != null ? result.getSuggestedCommands() : java.util.Collections.emptyList());
        response.put("executionTimeMs", result.getExecutionTimeMs());
        response.put("conversationId", conversation != null ? conversation.getId() : null);
        return response;
    }

    /**
     * 智能问答（流式输出，SSE）
     * 事件约定：
     *   event: chunk   data: {"delta": "..."}        — 逐段下发的文本
     *   event: end     data: {"sources":..., "suggestedCommands":..., "conversationId":..., "intent":..., "executionTimeMs":...}
     *   event: error   data: {"message":"..."}
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        log.info("收到流式问答请求: {}", request.getQuery());
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        Long currentUserId = SecurityUtils.getCurrentUserId();

        sseExecutor.submit(() -> {
            long startNs = System.nanoTime();
            StringBuilder fullBuf = new StringBuilder();
            try {
                // 1) 会话落盘 + 用户消息写入
                ChatConversation conversation = chatHistoryService.getOrCreateConversation(
                        request.getSessionId(), currentUserId, request.getQuery());
                if (conversation != null) {
                    chatHistoryService.appendUserMessage(conversation.getId(), request.getQuery());
                }

                // 2) 构造 prompt（系统提示 + 用户查询，简单拼接以支持真流式）
                String prompt = buildChatPrompt(request.getQuery());

                // 3) 真流式调用：每收到一个 delta 立即 SSE 推送
                String fullText = llmFallbackHandler.streamSync(prompt, piece -> {
                    if (piece == null || piece.isEmpty()) return;
                    fullBuf.append(piece);
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("delta", piece);
                    try {
                        emitter.send(SseEmitter.event().name("chunk")
                                .data(toJson(payload), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        throw new RuntimeException("SSE 推送中断", e);
                    }
                });

                String answer = (fullText != null && !fullText.isEmpty()) ? fullText : fullBuf.toString();
                long execMs = (System.nanoTime() - startNs) / 1_000_000L;

                // 4) 从回答中规则抽取命令建议（简化策略，流式无法通过 Orchestrator）
                List<Map<String, Object>> suggestedCommands = extractSuggestedCommands(answer);

                // 5) 落盘助手消息
                if (conversation != null) {
                    chatHistoryService.appendAssistantMessage(
                            conversation.getId(),
                            answer,
                            java.util.Collections.emptyList(),
                            suggestedCommands,
                            "STREAM",
                            "OrchestratorAgent(stream)",
                            execMs
                    );
                }

                // 6) 下发 end 事件
                Map<String, Object> endPayload = new HashMap<>();
                endPayload.put("success", true);
                endPayload.put("intent", "STREAM");
                endPayload.put("sources", java.util.Collections.emptyList());
                endPayload.put("suggestedCommands", suggestedCommands);
                endPayload.put("executionTimeMs", execMs);
                endPayload.put("conversationId", conversation != null ? conversation.getId() : null);
                emitter.send(SseEmitter.event().name("end")
                        .data(toJson(endPayload), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException ioe) {
                log.warn("SSE 连接已关闭: {}", ioe.getMessage());
                emitter.completeWithError(ioe);
            } catch (Exception e) {
                log.error("流式问答异常", e);
                try {
                    Map<String, Object> err = new HashMap<>();
                    err.put("message", e.getMessage() != null ? e.getMessage() : "内部错误");
                    emitter.send(SseEmitter.event().name("error")
                            .data(toJson(err), MediaType.APPLICATION_JSON));
                } catch (IOException ignored) { /* 连接已断 */ }
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(err -> log.warn("SSE error: {}", err.getMessage()));
        return emitter;
    }

    /** 流式对话提示词拼接 */
    private String buildChatPrompt(String userQuery) {
        return "你是一位专业的运维助手，精通 Linux 系统、网络、数据库与 Netdata 监控。\n"
             + "请用中文回答用户问题，返回结构清晰、可操作的答案。\n"
             + "如需建议命令，请在 Markdown 代码块中使用 ```bash 包裹，便于系统提取。\n\n"
             + "用户问题: " + (userQuery == null ? "" : userQuery);
    }

    /** 从带 Markdown 标记的文本中抽取 bash 代码块作为建议命令 */
    private List<Map<String, Object>> extractSuggestedCommands(String text) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;
        Pattern p = Pattern.compile("```(?:bash|shell|sh)?\\s*\\n([\\s\\S]*?)\\n?```", Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        int idx = 0;
        while (m.find() && idx < 5) {
            String block = m.group(1);
            if (block == null) continue;
            for (String line : block.split("\\r?\\n")) {
                String cmd = line.trim();
                if (cmd.isEmpty() || cmd.startsWith("#")) continue;
                Map<String, Object> item = new HashMap<>();
                item.put("command", cmd);
                item.put("description", "从 AI 回答中提取的建议命令");
                String risk = assessRiskLevel(cmd);
                item.put("riskLevel", risk);
                item.put("requiresApproval", !"LOW".equals(risk));
                list.add(item);
                idx++;
                if (idx >= 5) break;
            }
        }
        return list;
    }

    /** 简化的风险分级（和后端审计服务关键词保持一致） */
    private String assessRiskLevel(String cmd) {
        String c = cmd.toLowerCase();
        if (c.contains("rm -rf") || c.contains("mkfs") || c.contains("dd if=") ||
                c.contains("shutdown") || c.contains("reboot") || c.contains("kill -9")) {
            return "HIGH";
        }
        if (c.startsWith("sudo") || c.contains("systemctl restart") || c.contains("kill ") ||
                c.contains("iptables") || c.contains("chmod") || c.contains("chown") ||
                c.contains("mv ") || c.contains("rm ")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    /**
     * 分页查询当前用户的会话列表
     */
    @GetMapping("/chat/conversations")
    public R<PageResult<ChatConversation>> listConversations(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(chatHistoryService.listConversations(current, size, userId));
    }

    /**
     * 获取某会话下的所有消息
     */
    @GetMapping("/chat/conversations/{id}/messages")
    public R<List<ChatMessage>> getMessages(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(chatHistoryService.getMessages(id, userId));
    }

    /**
     * 删除会话（级联删除消息）
     */
    @DeleteMapping("/chat/conversations/{id}")
    public R<Void> deleteConversation(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        chatHistoryService.deleteConversation(id, userId);
        return R.ok();
    }

    /**
     * 清空会话消息但保留会话
     */
    @DeleteMapping("/chat/conversations/{id}/messages")
    public R<Void> clearMessages(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        chatHistoryService.clearMessages(id, userId);
        return R.ok();
    }

    // ========== 请求/响应模型 ==========

    @lombok.Data
    public static class ChatRequest {
        private String sessionId;
        private String userId;
        private String query;
    }
}
