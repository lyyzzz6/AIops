package com.netdata.ops.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netdata.ops.core.agent.*;
import com.netdata.ops.core.ai.LLMFallbackHandler;
import com.netdata.ops.core.rag.HybridRetriever;
import com.netdata.ops.core.rag.RAGService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final RAGService ragService;
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
     *   event: thinking data: {"content": "..."}    — 模型思考过程
     *   event: chunk   data: {"delta": "..."}        — 逐段下发的文本
     *   event: end     data: {"sources":..., "suggestedCommands":..., "conversationId":..., "intent":..., "executionTimeMs":...}
     *   event: error   data: {"message":"..."}
     *   event: ping    data: (empty)                 — 心跳保活
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        log.info("收到流式问答请求: {}", request.getQuery());
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        Long currentUserId = SecurityUtils.getCurrentUserId();

        sseExecutor.submit(() -> {
            long startNs = System.nanoTime();
            AtomicBoolean running = new AtomicBoolean(true);
            
            // 启动心跳线程，防止连接超时（每10秒发送一次ping）
            Thread heartbeatThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        emitter.send(SseEmitter.event().name("ping"));
                        Thread.sleep(10000);
                    } catch (Exception e) {
                        log.debug("心跳线程退出: {}", e.getMessage());
                        break;
                    }
                }
            }, "sse-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            try {
                // 1) 会话落盘 + 用户消息写入
                ChatConversation conversation = chatHistoryService.getOrCreateConversation(
                        request.getSessionId(), currentUserId, request.getQuery());
                if (conversation != null) {
                    chatHistoryService.appendUserMessage(conversation.getId(), request.getQuery());
                }

                // 2) 构建 AgentContext
                String userIdStr = currentUserId != null ? String.valueOf(currentUserId) : request.getUserId();
                AgentContext context = AgentContext.builder()
                        .sessionId(request.getSessionId())
                        .userId(userIdStr)
                        .query(request.getQuery())
                        .build();

                // 3) 发送思考过程
                String thinking = "正在分析您的问题...\n\n已启动 OrchestratorAgent 进行意图识别和任务路由。";
                Map<String, Object> thinkingPayload = new HashMap<>();
                thinkingPayload.put("content", thinking);
                emitter.send(SseEmitter.event().name("thinking")
                        .data(toJson(thinkingPayload), MediaType.APPLICATION_JSON));

                // 4) 调用 OrchestratorAgent 进行意图分类和执行
                AgentResult result = orchestratorAgent.execute(context);
                long execMs = (System.nanoTime() - startNs) / 1_000_000L;

                // 5) 将 AgentResult 转换为流式输出
                if (result.getResponse() != null && !result.getResponse().isEmpty()) {
                    String response = result.getResponse();
                    int chunkSize = 10;
                    long startTime = System.currentTimeMillis();
                    
                    for (int i = 0; i < response.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, response.length());
                        String chunk = response.substring(i, end);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("delta", chunk);
                        try {
                            emitter.send(SseEmitter.event().name("chunk")
                                    .data(toJson(payload), MediaType.APPLICATION_JSON));
                            
                            long elapsed = System.currentTimeMillis() - startTime;
                            long remaining = response.length() - i;
                            if (remaining > 0 && elapsed < 2000) {
                                long delay = Math.max(5, Math.min(30, (2000 - elapsed) / (remaining / chunkSize + 1)));
                                Thread.sleep(delay);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("SSE 推送中断", e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                // 6) 准备 end 事件数据
                String intentStr = result.getIntentType() != null ? result.getIntentType().name() : "UNKNOWN";
                List<Map<String, Object>> suggestedCommands = convertCommandSuggestions(result.getSuggestedCommands());

                // 7) 落盘助手消息
                if (conversation != null) {
                    chatHistoryService.appendAssistantMessage(
                            conversation.getId(),
                            result.getResponse(),
                            result.getSources(),
                            suggestedCommands,
                            intentStr,
                            result.getAgentName(),
                            execMs);
                }

                // 8) 下发 end 事件（优化：工具调用历史太大，改为发送摘要）
                Map<String, Object> endPayload = new HashMap<>();
                endPayload.put("success", result.isSuccess());
                endPayload.put("intent", intentStr);
                endPayload.put("sources", result.getSources() != null ? result.getSources() : new ArrayList<>());
                endPayload.put("suggestedCommands", suggestedCommands);
                endPayload.put("executionTimeMs", execMs);
                endPayload.put("conversationId", conversation != null ? conversation.getId() : null);
                if (result.getDiagnosisReport() != null) {
                    endPayload.put("diagnosisReport", result.getDiagnosisReport());
                }
                // 优化：工具调用历史太大（包含大量监控数据），改为发送摘要
                if (result.getToolCallHistory() != null && !result.getToolCallHistory().isEmpty()) {
                    Map<String, Object> toolSummary = new HashMap<>();
                    toolSummary.put("totalCalls", result.getToolCallHistory().size());
                    toolSummary.put("toolNames", result.getToolCallHistory().stream()
                            .map(call -> {
                                Map<String, Object> summary = new HashMap<>();
                                summary.put("toolName", call.getToolName());
                                summary.put("success", call.isSuccess());
                                summary.put("durationMs", call.getDurationMs());
                                // 只保留工具名称、成功状态和耗时，不发送完整结果
                                return summary;
                            })
                            .collect(Collectors.toList()));
                    endPayload.put("toolCallSummary", toolSummary);
                }
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
            } finally {
                // 停止心跳线程
                running.set(false);
                heartbeatThread.interrupt();
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(err -> log.warn("SSE error: {}", err.getMessage()));
        return emitter;
    }

    /**
     * 转换命令建议列表格式
     */
    private List<Map<String, Object>> convertCommandSuggestions(List<AgentResult.CommandSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return new ArrayList<>();
        }
        return suggestions.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("command", s.getCommand());
            map.put("description", s.getDescription());
            map.put("riskLevel", s.getRiskLevel());
            map.put("requiresApproval", s.isRequiresApproval());
            return map;
        }).collect(java.util.stream.Collectors.toList());
    }

    /** 流式对话提示词拼接（不带RAG） */
    private String buildChatPrompt(String userQuery) {
        return "你是一位专业的运维助手，精通 Linux 系统、网络、数据库与 Netdata 监控。\n"
             + "请用中文回答用户问题，返回结构清晰、可操作的答案。\n"
             + "如需建议命令，请在 Markdown 代码块中使用 ```bash 包裹，便于系统提取。\n\n"
             + "用户问题: " + (userQuery == null ? "" : userQuery);
    }

    /** 构建带RAG上下文的流式对话提示词 */
    private String buildRAGChatPrompt(String userQuery, List<HybridRetriever.RetrievalResult> ragResults) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("你是 NetData 智能运维系统的知识问答助手。\n");
        prompt.append("请基于以下参考资料回答用户的问题。\n\n");
        prompt.append("要求：\n");
        prompt.append("1. 仅基于提供的参考资料回答，不要编造信息\n");
        prompt.append("2. 如果参考资料不足以回答问题，请诚实说明\n");
        prompt.append("3. 在回答中标注引用来源（如 [1]、[2]）\n");
        prompt.append("4. 使用清晰的结构化格式（Markdown）\n");
        prompt.append("5. 对专业术语给出简要解释\n");
        prompt.append("6. 如果多条参考资料有冲突，请指出差异并给出综合判断\n");
        prompt.append("7. 如果用户询问如何操作或执行命令，必须在回答最后用 ```bash 代码块给出具体的可执行命令\n\n");
        
        if (ragResults != null && !ragResults.isEmpty()) {
            prompt.append("参考资料：\n");
            for (int i = 0; i < ragResults.size(); i++) {
                HybridRetriever.RetrievalResult result = ragResults.get(i);
                prompt.append(String.format("### [%d] %s\n", i + 1, result.getTitle()));
                prompt.append(String.format("来源: %s | 相关度: %.3f\n", result.getSource(), result.getRrfScore()));
                prompt.append(result.getContent());
                prompt.append("\n\n");
            }
        } else {
            prompt.append("参考资料：无\n\n");
        }
        
        prompt.append("用户问题: ").append(userQuery != null ? userQuery : "").append("\n");
        prompt.append("回答：\n");
        
        return prompt.toString();
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
                if (!isLikelyCommand(cmd)) continue;
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

    private boolean isLikelyCommand(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.matches("^[\\u4e00-\\u9fa5\\s.,;:!?。，；：！？]+$")) return false;
        String lower = text.toLowerCase();
        String[] prefixes = {"top", "ps", "ls", "cd ", "cat ", "grep ", "echo ", "curl ", "wget ", "df ", "du ", "free",
                "uptime", "netstat", "ss ", "iptables", "systemctl", "service ", "docker ", "kubectl ", "ssh ",
                "scp ", "rsync ", "tail ", "head ", "awk ", "sed ", "sort ", "uniq ", "find ", "xargs ",
                "nohup ", "kill ", "pkill ", "htop", "vmstat", "iostat", "mpstat", "pidstat", "sar ",
                "ping ", "traceroute", "nslookup", "dig ", "firewall-cmd", "chmod", "chown", "chgrp",
                "mkdir", "rmdir", "touch ", "mv ", "cp ", "rm ", "tar ", "zip ", "unzip ", "gzip ",
                "bzip2", "xz ", "dd ", "less ", "more ", "vi ", "vim ", "nano ", "python", "python3",
                "node ", "ls -", "ps -", "netstat -", "ss -", "free -", "df -", "du -", "uptime",
                "cd /", "cd ~", "./", "bash ", "sh ", "source "};
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
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

    /**
     * 生成模型思考过程描述
     * 
     * @param userQuery 用户问题
     * @param ragResults RAG检索结果
     * @return 思考过程文本
     */
    private String generateThinking(String userQuery, List<HybridRetriever.RetrievalResult> ragResults) {
        StringBuilder thinking = new StringBuilder();
        
        thinking.append("用户问了：\"").append(userQuery).append("\"\n\n");
        
        if (ragResults != null && !ragResults.isEmpty()) {
            thinking.append("我在知识库中检索到 ").append(ragResults.size()).append(" 条相关知识：\n");
            for (int i = 0; i < ragResults.size(); i++) {
                HybridRetriever.RetrievalResult r = ragResults.get(i);
                thinking.append(String.format("  [%d] 《%s》- 相关度: %.2f%%\n", 
                        i + 1, r.getTitle(), r.getRrfScore() * 100));
            }
            thinking.append("\n我将基于这些参考资料来综合回答用户的问题。");
        } else {
            thinking.append("知识库中未检索到相关知识，我将基于自身知识进行回答。");
        }
        
        return thinking.toString();
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