package com.netdata.ops.controller;

import com.netdata.ops.core.agent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ============================================================
 * 智能运维 API 控制器
 * ============================================================
 * 
 * API 端点：
 * - POST /chat: 智能问答
 * - POST /diagnose: 故障诊断
 * - POST /execute: 命令执行
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
    
    /**
     * 智能问答
     *
     * @param request 请求体
     * @return 响应
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        log.info("收到问答请求: {}", request.getQuery());
        
        // 构建上下文
        AgentContext context = AgentContext.builder()
            .sessionId(request.getSessionId())
            .userId(request.getUserId())
            .query(request.getQuery())
            .build();
        
        // 执行 Agent
        AgentResult result = orchestratorAgent.execute(context);
        
        return Map.of(
            "success", result.isSuccess(),
            "response", result.getResponse() != null ? result.getResponse() : "",
            "intent", result.getIntentType() != null ? result.getIntentType().name() : "UNKNOWN",
            "sources", result.getSources() != null ? result.getSources() : java.util.Collections.emptyList(),
            "suggestedCommands", result.getSuggestedCommands() != null ? result.getSuggestedCommands() : java.util.Collections.emptyList(),
            "executionTimeMs", result.getExecutionTimeMs()
        );
    }
    
    // ========== 请求/响应模型 ==========
    
    @lombok.Data
    public static class ChatRequest {
        private String sessionId;
        private String userId;
        private String query;
    }
}
