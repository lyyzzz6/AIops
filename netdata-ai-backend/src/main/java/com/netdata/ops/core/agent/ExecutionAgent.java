package com.netdata.ops.core.agent;

import com.netdata.ops.core.agent.event.AgentEventBus;
import com.netdata.ops.core.agent.event.AgentMessage;
import com.netdata.ops.core.agent.event.AgentMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * ============================================================
 * Execution Agent - Human-in-the-Loop 命令执行
 * ============================================================
 * 
 * 职责：
 * 1. 解析用户命令
 * 2. 风险评估
 * 3. 生成审批请求
 * 4. 执行命令（审批通过后）
 * 5. 记录审计日志
 *
 * 安全机制：
 * - 黑名单：危险命令禁止执行
 * - 白名单：安全命令自动执行
 * - 灰名单：需要人工审批
 *
 * 风险评估维度：
 * 1. 命令类型（权重 40%）
 * 2. 影响范围（权重 30%）
 * 3. 可逆性（权重 20%）
 * 4. 执行频率（权重 10%）
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
@Component
public class ExecutionAgent extends BaseAgent implements AgentMessageHandler {
    
    private final AgentEventBus agentEventBus;
    private final AgentStateManager agentStateManager;
    private final DistributedLockService distributedLockService;
    
    /**
     * 黑名单命令（禁止执行）
     */
    private static final List<Pattern> BLACKLIST = List.of(
        Pattern.compile("rm\\s+-rf\\s+/.*"),
        Pattern.compile("rm\\s+-rf\\s+\\*.*"),
        Pattern.compile("mkfs.*"),
        Pattern.compile("dd\\s+if=.*"),
        Pattern.compile(".*>\\s*/dev/sd.*"),
        Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};\\s*:")
    );
    
    /**
     * 白名单命令（自动执行）
     */
    private static final List<Pattern> WHITELIST = List.of(
        Pattern.compile("systemctl\\s+status\\s+.*"),
        Pattern.compile("systemctl\\s+list-.*"),
        Pattern.compile("journalctl.*"),
        Pattern.compile("cat\\s+/var/log/.*"),
        Pattern.compile("ls(\\s+\\S+)*"),
        Pattern.compile("ps\\s+aux.*"),
        Pattern.compile("netstat.*"),
        Pattern.compile("df\\s+-h"),
        Pattern.compile("free\\s+-h"),
        Pattern.compile("top\\s+-n\\s+1.*"),
        Pattern.compile("curl.*"),
        Pattern.compile("ping\\s+-c\\s+\\d+.*")
    );
    
    /**
     * 风险阈值
     */
    private static final int RISK_LOW = 30;
    private static final int RISK_MEDIUM = 60;
    private static final int RISK_HIGH = 80;
    
    public ExecutionAgent(AgentEventBus agentEventBus, AgentStateManager agentStateManager,
                          DistributedLockService distributedLockService,
                          AgentMetrics agentMetrics, List<AgentInterceptor> interceptors) {
        super("ExecutionAgent", AgentType.EXECUTION, agentMetrics, interceptors);
        this.agentEventBus = agentEventBus;
        this.agentStateManager = agentStateManager;
        this.distributedLockService = distributedLockService;
        // 向事件总线注册自己
        this.agentEventBus.registerHandler(this);
    }
    
    // ==================== AgentMessageHandler 实现 ====================
    
    @Override
    public String getAgentName() {
        return "ExecutionAgent";
    }
    
    @Override
    public boolean supports(AgentMessage.MessageType type) {
        return type == AgentMessage.MessageType.APPROVAL_RESPONSE;
    }
    
    @Override
    public void handle(AgentMessage message) {
        if (message.getType() == AgentMessage.MessageType.APPROVAL_RESPONSE) {
            Map<String, Object> payload = message.getPayload();
            String approvalId = (String) payload.get("approvalId");
            boolean approved = (Boolean) payload.getOrDefault("approved", false);
            String approver = (String) payload.getOrDefault("approver", "system");
            
            if (approved) {
                // 更新审批状态
                agentStateManager.updateApprovalStatus(approvalId,
                        AgentStateManager.ApprovalStatus.APPROVED, approver);
                // 获取审批请求中的命令并执行
                ApprovalRequest request = agentStateManager.getApprovalRequest(approvalId);
                if (request != null) {
                    // 分布式锁防重复执行
                    boolean locked = distributedLockService.lockCommandExecution(
                            request.getCommand(), approvalId);
                    if (!locked) {
                        log.warn("[ExecutionAgent] 命令已在执行中，跳过重复执行: approvalId={}", approvalId);
                        return;
                    }
                    try {
                        executeCommand(request.getCommand(), true);
                        agentStateManager.updateApprovalStatus(approvalId,
                                AgentStateManager.ApprovalStatus.EXECUTED, approver);
                    } finally {
                        distributedLockService.unlockCommandExecution(
                                request.getCommand(), approvalId);
                    }
                }
            } else {
                agentStateManager.updateApprovalStatus(approvalId,
                        AgentStateManager.ApprovalStatus.REJECTED, approver);
            }
            
            log.info("[ExecutionAgent] 处理审批响应: approvalId={}, approved={}", approvalId, approved);
        }
    }
    
    // ==================== Agent 核心逻辑 ====================
    
    @Override
    protected AgentResult doExecute(AgentContext context) {
        log.info("Execution Agent 处理命令: {}", context.getQuery());
        
        // 从上下文提取命令
        String command = extractCommand(context.getQuery());
        
        if (command == null || command.isEmpty()) {
            return AgentResult.builder()
                .success(false)
                .errorMessage("无法识别要执行的命令，请明确指定")
                .build();
        }
        
        // 1. 黑名单检查
        if (isBlacklisted(command)) {
            log.warn("命令在黑名单中，拒绝执行: {}", command);
            return AgentResult.builder()
                .success(false)
                .errorMessage("该命令被列为危险命令，禁止执行")
                .suggestedCommands(List.of(
                    AgentResult.CommandSuggestion.builder()
                        .command(command)
                        .description("危险命令，已阻止")
                        .riskLevel("CRITICAL")
                        .requiresApproval(true)
                        .build()
                ))
                .build();
        }
        
        // 2. 白名单检查
        if (isWhitelisted(command)) {
            log.info("命令在白名单中，自动执行: {}", command);
            return executeCommand(command, false);
        }
        
        // 3. 风险评估
        RiskAssessment assessment = assessRisk(command);
        log.info("风险评估: {}, 分数: {}", assessment.getLevel(), assessment.getScore());
        
        // 4. 根据风险等级决定执行方式
        if (assessment.getScore() < RISK_LOW) {
            // 低风险：自动执行
            return executeCommand(command, false);
        } else {
            // 中高风险：需要审批
            return createApprovalRequest(command, assessment);
        }
    }
    
    /**
     * 从用户输入提取命令
     */
    private String extractCommand(String query) {
        // 简化实现：假设用户直接输入命令
        // 生产环境应使用 NLP 解析
        
        // 去除常见前缀
        query = query.replaceFirst("^(执行|运行|帮我|请)?\\s*", "");
        
        return query.trim();
    }
    
    /**
     * 检查是否在黑名单
     */
    private boolean isBlacklisted(String command) {
        return BLACKLIST.stream()
            .anyMatch(pattern -> pattern.matcher(command).matches());
    }
    
    /**
     * 检查是否在白名单
     */
    private boolean isWhitelisted(String command) {
        return WHITELIST.stream()
            .anyMatch(pattern -> pattern.matcher(command).matches());
    }
    
    /**
     * 风险评估
     */
    private RiskAssessment assessRisk(String command) {
        int score = 0;
        
        // 维度1：命令类型（权重 40%）
        score += assessCommandType(command);
        
        // 维度2：影响范围（权重 30%）
        score += assessImpactScope(command);
        
        // 维度3：可逆性（权重 20%）
        score += assessReversibility(command);
        
        // 维度4：执行频率（权重 10%）
        score += assessFrequency(command);
        
        String level;
        if (score >= RISK_HIGH) {
            level = "HIGH";
        } else if (score >= RISK_MEDIUM) {
            level = "MEDIUM";
        } else {
            level = "LOW";
        }
        
        return new RiskAssessment(score, level);
    }
    
    /**
     * 评估命令类型
     */
    private int assessCommandType(String command) {
        if (command.contains("rm ")) return 40;
        if (command.contains("delete") || command.contains("drop")) return 35;
        if (command.contains("restart") || command.contains("reload")) return 20;
        if (command.contains("stop") || command.contains("kill")) return 25;
        if (command.contains("start")) return 15;
        return 10;  // 默认低风险
    }
    
    /**
     * 评估影响范围
     */
    private int assessImpactScope(String command) {
        if (command.contains("/*") || command.contains("all")) return 30;
        if (command.contains("/etc/") || command.contains("config")) return 25;
        if (command.contains("service") || command.contains("systemctl")) return 20;
        return 10;
    }
    
    /**
     * 评估可逆性
     */
    private int assessReversibility(String command) {
        if (command.contains("rm ") || command.contains("delete")) return 20;
        if (command.contains("restart")) return 10;
        if (command.contains("status") || command.contains("list")) return 0;
        return 5;
    }
    
    /**
     * 评估执行频率
     */
    private int assessFrequency(String command) {
        // 首次执行的命令风险稍高
        return 5;
    }
    
    /**
     * 执行命令
     */
    private AgentResult executeCommand(String command, boolean approved) {
        log.info("执行命令: {}, 已审批: {}", command, approved);
        
        try {
            // 生产环境：实际执行命令
            // Process process = Runtime.getRuntime().exec(command);
            
            // 模拟执行结果
            String output = "命令执行成功\n输出：\n[模拟输出]";
            
            return AgentResult.builder()
                .success(true)
                .response(String.format(
                    "✅ 命令执行成功\n\n" +
                    "**命令：** `%s`\n\n" +
                    "**输出：**\n```\n%s\n```",
                    command, output
                ))
                .suggestedCommands(List.of(
                    AgentResult.CommandSuggestion.builder()
                        .command(command)
                        .description("已执行")
                        .riskLevel("LOW")
                        .requiresApproval(approved)
                        .build()
                ))
                .build();
            
        } catch (Exception e) {
            log.error("命令执行失败", e);
            return AgentResult.builder()
                .success(false)
                .errorMessage("命令执行失败：" + e.getMessage())
                .build();
        }
    }
    
    /**
     * 创建审批请求（事件驱动版）
     */
    private AgentResult createApprovalRequest(String command, RiskAssessment assessment) {
        log.info("创建审批请求: {}, 风险: {}", command, assessment.getLevel());
        
        // 1. 使用 AgentStateManager 创建审批请求并持久化
        ApprovalRequest request = ApprovalRequest.builder()
                .command(command)
                .riskLevel(assessment.getLevel())
                .riskScore(assessment.getScore())
                .build();
        String approvalId = agentStateManager.createApprovalRequest(request);
        
        // 2. 发布审批请求事件到事件总线
        String traceId = MDC.get("traceId");
        agentEventBus.publish(AgentMessage.builder()
                .sourceAgent("ExecutionAgent")
                .type(AgentMessage.MessageType.APPROVAL_REQUEST)
                .traceId(traceId != null ? traceId : UUID.randomUUID().toString().substring(0, 8))
                .payload(Map.of(
                    "approvalId", approvalId,
                    "command", command,
                    "riskLevel", assessment.getLevel(),
                    "riskScore", assessment.getScore()
                ))
                .priority(8)
                .build());
        
        // 3. 返回等待审批的结果
        return AgentResult.builder()
            .success(true)
            .response(String.format(
                "⚠️ **命令需要审批**\n\n" +
                "**审批 ID：** %s\n\n" +
                "**命令：** `%s`\n\n" +
                "**风险等级：** %s (分数: %d)\n\n" +
                "**风险评估：**\n" +
                "- 命令类型：%s\n" +
                "- 影响范围：%s\n" +
                "- 可逆性：%s\n\n" +
                "请在审批界面确认或拒绝此命令。",
                approvalId, command, assessment.getLevel(), assessment.getScore(),
                getCommandTypeDesc(command),
                getImpactScopeDesc(command),
                getReversibilityDesc(command)
            ))
            .suggestedCommands(List.of(
                AgentResult.CommandSuggestion.builder()
                    .command(command)
                    .description("等待审批")
                    .riskLevel(assessment.getLevel())
                    .requiresApproval(true)
                    .build()
            ))
            .build();
    }
    
    private String getCommandTypeDesc(String command) {
        if (command.contains("restart")) return "服务重启";
        if (command.contains("stop")) return "服务停止";
        if (command.contains("start")) return "服务启动";
        return "其他操作";
    }
    
    private String getImpactScopeDesc(String command) {
        if (command.contains("service")) return "影响单个服务";
        if (command.contains("system")) return "影响系统级";
        return "影响有限";
    }
    
    private String getReversibilityDesc(String command) {
        if (command.contains("restart")) return "可逆（可再次重启）";
        if (command.contains("stop")) return "可逆（可重新启动）";
        return "需要进一步评估";
    }
    
    // ========== 内部类 ==========
    
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class RiskAssessment {
        private int score;
        private String level;
    }
}
