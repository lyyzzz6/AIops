package com.netdata.ops.core.agent;

import com.netdata.ops.core.ai.LLMFallbackHandler;
import com.netdata.ops.core.agent.event.AgentEventBus;
import com.netdata.ops.core.agent.event.AgentMessage;
import com.netdata.ops.core.agent.event.AgentMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Execution Agent - Human-in-the-Loop 命令执行
 * ============================================================
 * 
 * 职责：
 * 1. 解析用户命令（使用 LLM 将自然语言转换为 shell 命令）
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
    private final LLMFallbackHandler llmHandler;
    
    /** 命令执行默认超时时间（秒） */
    @Value("${execution.command.timeout:30}")
    private int commandTimeoutSeconds;
    
    /** 最大输出行数限制 */
    @Value("${execution.output.max-lines:1000}")
    private int maxOutputLines;
    
    /** 最大输出字节数限制 */
    @Value("${execution.output.max-bytes:102400}")
    private int maxOutputBytes;
    
    /**
     * 黑名单命令（禁止执行）
     * 支持正则匹配，覆盖所有危险操作
     */
    private static final List<Pattern> BLACKLIST = List.of(
        // 递归删除根目录或系统目录
        Pattern.compile("rm\\s+-rf\\s+/.*"),
        Pattern.compile("rm\\s+-rf\\s+\\*.*"),
        Pattern.compile("rm\\s+-rf\\s+\\.\\.*"),
        // 文件系统操作
        Pattern.compile("mkfs.*"),
        Pattern.compile("mkfs\\s+"),
        Pattern.compile("dd\\s+if=.*"),
        Pattern.compile(".*>\\s*/dev/sd.*"),
        Pattern.compile(".*>\\s*/dev/nvme.*"),
        Pattern.compile("fdisk.*"),
        Pattern.compile("parted.*"),
        // Fork炸弹
        Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};\\s*:"),
        Pattern.compile("\\.\\.\\.\\{\\s*:\\|:\\s*&\\s*\\};\\s*:"),
        // 提权操作
        Pattern.compile("chmod\\s+[47]777.*"),
        Pattern.compile("chown\\s+-R\\s+.*:root.*"),
        Pattern.compile("passwd\\s+root.*"),
        // 网络监听端口反弹shell
        Pattern.compile("nc\\s+-[el].*"),
        Pattern.compile("/dev/tcp/.*"),
        Pattern.compile("bash\\s+-i.*"),
        Pattern.compile("python.*-c.*socket.*"),
        // 关机重启
        Pattern.compile("shutdown.*"),
        Pattern.compile("init\\s+0.*"),
        Pattern.compile("halt.*"),
        Pattern.compile("poweroff.*"),
        // 系统配置修改
        Pattern.compile("iptables\\s+-F.*"),
        Pattern.compile("ip\\s+addr\\s+flush.*"),
        // 下载执行
        Pattern.compile("curl.*\\|\\s*bash.*"),
        Pattern.compile("wget.*\\|\\s*bash.*"),
        Pattern.compile("python.*\\|\\s*bash.*")
    );
    
    /**
     * 白名单命令（自动执行 - 仅限只读/低风险操作）
     */
    private static final List<Pattern> WHITELIST = List.of(
        // 系统状态查看
        Pattern.compile("systemctl\\s+status\\s+.*"),
        Pattern.compile("systemctl\\s+is-active\\s+.*"),
        Pattern.compile("systemctl\\s+list-units.*"),
        Pattern.compile("systemctl\\s+list-unit-files.*"),
        // 日志查看
        Pattern.compile("journalctl.*"),
        Pattern.compile("cat\\s+/var/log/.*"),
        Pattern.compile("tail\\s+.*"),
        Pattern.compile("head\\s+.*"),
        Pattern.compile("less\\s+.*"),
        Pattern.compile("grep\\s+.*"),
        // 文件查看
        Pattern.compile("ls(?!.*\\|).*"),
        Pattern.compile("pwd"),
        Pattern.compile("stat\\s+.*"),
        Pattern.compile("file\\s+.*"),
        Pattern.compile("cat\\s+.*"),
        Pattern.compile("wc\\s+.*"),
        // 进程查看
        Pattern.compile("ps\\s+aux.*"),
        Pattern.compile("ps\\s+-[ef].*"),
        Pattern.compile("top\\s+-n\\s+1.*"),
        Pattern.compile("htop.*"),
        Pattern.compile("pidof\\s+.*"),
        Pattern.compile("pgrep.*"),
        Pattern.compile("pstree.*"),
        // 网络查看
        Pattern.compile("netstat.*"),
        Pattern.compile("ss\\s+-[ltunp].*"),
        Pattern.compile("ip\\s+addr.*"),
        Pattern.compile("ip\\s+link.*"),
        Pattern.compile("ip\\s+route.*"),
        Pattern.compile("ping\\s+-c\\s+\\d+.*"),
        Pattern.compile("curl\\s+.*"),
        Pattern.compile("wget\\s+.*--spider.*"),
        Pattern.compile("nslookup\\s+.*"),
        Pattern.compile("dig\\s+.*"),
        Pattern.compile("host\\s+.*"),
        Pattern.compile("traceroute\\s+.*"),
        Pattern.compile("tracepath\\s+.*"),
        // 资源查看
        Pattern.compile("df\\s+-h.*"),
        Pattern.compile("du\\s+.*"),
        Pattern.compile("free\\s+-h.*"),
        Pattern.compile("uptime.*"),
        Pattern.compile("whoami.*"),
        Pattern.compile("id.*"),
        Pattern.compile("uname\\s+-a.*"),
        // Docker/容器
        Pattern.compile("docker\\s+ps.*"),
        Pattern.compile("docker\\s+images.*"),
        Pattern.compile("docker\\s+logs\\s+.*"),
        Pattern.compile("docker\\s+inspect\\s+.*"),
        Pattern.compile("docker\\s+stats\\s+.*"),
        Pattern.compile("docker\\s+top\\s+.*"),
        Pattern.compile("docker\\s+network\\s+ls.*"),
        // 服务健康检查
        Pattern.compile("curl\\s+.*localhost.*"),
        Pattern.compile("curl\\s+.*127\\.0\\.0\\.1.*")
    );
    
    /**
     * 危险命令关键词（用于增强黑名单检测）
     */
    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
        "rm -rf",
        "mkfs",
        ":(){:|:&};:",
        "chmod 777",
        "chmod 4777",
        "> /dev/sd",
        "shutdown",
        "init 0",
        "nc -e",
        "nc -l",
        "/dev/tcp",
        "bash -i",
        "curl | bash",
        "wget | bash"
    );
    
    /**
     * 风险阈值
     */
    private static final int RISK_LOW = 30;
    private static final int RISK_MEDIUM = 60;
    private static final int RISK_HIGH = 80;
    
    public ExecutionAgent(AgentEventBus agentEventBus, AgentStateManager agentStateManager,
                          DistributedLockService distributedLockService, LLMFallbackHandler llmHandler,
                          AgentMetrics agentMetrics, List<AgentInterceptor> interceptors) {
        super("ExecutionAgent", AgentType.EXECUTION, agentMetrics, interceptors);
        this.agentEventBus = agentEventBus;
        this.agentStateManager = agentStateManager;
        this.distributedLockService = distributedLockService;
        this.llmHandler = llmHandler;
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
        
        // 从上下文提取命令（使用 LLM 将自然语言转换为 shell 命令）
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
     * 使用 LLM 将自然语言转换为实际的 shell 命令
     */
    private String extractCommand(String query) {
        // 如果已经是 shell 命令（包含空格、路径、参数等），直接返回
        if (isShellCommand(query)) {
            log.info("[ExecutionAgent] 检测到 shell 命令，直接使用: {}", query);
            return query.trim();
        }
        
        // 否则使用 LLM 将自然语言转换为 shell 命令
        log.info("[ExecutionAgent] 使用 LLM 将自然语言转换为 shell 命令: {}", query);
        String prompt = buildCommandGenerationPrompt(query);
        
        try {
            String response = llmHandler.call(prompt);
            log.info("[ExecutionAgent] LLM 生成的命令: {}", response);
            
            // 提取命令（去除 markdown 代码块标记）
            String command = extractCommandFromLLMResponse(response);
            
            if (command == null || command.isEmpty()) {
                log.warn("[ExecutionAgent] LLM 未能生成有效命令，使用原始输入");
                return query.trim();
            }
            
            return command.trim();
        } catch (Exception e) {
            log.error("[ExecutionAgent] LLM 调用失败: {}", e.getMessage(), e);
            // 降级：返回原始输入
            return query.trim();
        }
    }
    
    /**
     * 判断输入是否已经是 shell 命令
     */
    private boolean isShellCommand(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        // 包含常见 shell 命令特征
        String lower = input.toLowerCase().trim();
        
        // 检查是否以常见的 shell 命令开头
        if (lower.startsWith("ls ") ||
            lower.startsWith("rm ") ||
            lower.startsWith("cat ") ||
            lower.startsWith("grep ") ||
            lower.startsWith("ps ") ||
            lower.startsWith("docker ") ||
            lower.startsWith("systemctl ") ||
            lower.startsWith("curl ") ||
            lower.startsWith("wget ") ||
            lower.startsWith("ping ") ||
            lower.startsWith("df ") ||
            lower.startsWith("du ") ||
            lower.startsWith("free ") ||
            lower.startsWith("netstat ") ||
            lower.startsWith("ss ") ||
            lower.startsWith("ip ") ||
            lower.startsWith("tail ") ||
            lower.startsWith("head ") ||
            lower.startsWith("journalctl ") ||
            lower.startsWith("mkdir ") ||
            lower.startsWith("touch ") ||
            lower.startsWith("chmod ") ||
            lower.startsWith("chown ") ||
            lower.startsWith("cp ") ||
            lower.startsWith("mv ") ||
            lower.startsWith("find ") ||
            lower.startsWith("sed ") ||
            lower.startsWith("awk ") ||
            lower.startsWith("echo ") ||
            lower.startsWith("export ") ||
            lower.startsWith("env ") ||
            lower.startsWith("cd ") ||
            lower.startsWith("pwd") ||
            lower.startsWith("kill ") ||
            lower.startsWith("top ") ||
            lower.startsWith("htop ") ||
            lower.startsWith("nc ") ||
            lower.startsWith("dig ") ||
            lower.startsWith("nslookup ") ||
            lower.startsWith("host ") ||
            lower.startsWith("traceroute ") ||
            lower.startsWith("tracepath ") ||
            lower.startsWith("whoami") ||
            lower.startsWith("uname ") ||
            lower.startsWith("parted ") ||
            lower.startsWith("fdisk ") ||
            lower.startsWith("mkfs ") ||
            lower.startsWith("mount ") ||
            lower.startsWith("umount ") ||
            lower.startsWith("shutdown ") ||
            lower.startsWith("reboot ") ||
            lower.startsWith("halt ") ||
            lower.startsWith("poweroff ") ||
            lower.startsWith("iptables ") ||
            lower.startsWith("journalctl ") ||
            lower.startsWith("service ") ||
            lower.startsWith("apt ") ||
            lower.startsWith("yum ") ||
            lower.startsWith("dnf ") ||
            lower.startsWith("pip ") ||
            lower.startsWith("npm ") ||
            lower.startsWith("git ") ||
            lower.startsWith("make ") ||
            lower.startsWith("cmake ") ||
            lower.startsWith("java ") ||
            lower.startsWith("python ") ||
            lower.startsWith("node ") ||
            lower.startsWith("go ") ||
            lower.startsWith("rustc ") ||
            lower.startsWith("gcc ") ||
            lower.startsWith("g++ ") ||
            lower.startsWith("clang ") ||
            lower.startsWith("ssh ") ||
            lower.startsWith("scp ") ||
            lower.startsWith("rsync ") ||
            lower.startsWith("tar ") ||
            lower.startsWith("zip ") ||
            lower.startsWith("unzip ") ||
            lower.startsWith("gzip ") ||
            lower.startsWith("bzip2 ") ||
            lower.startsWith("xz ") ||
            lower.startsWith("openssl ") ||
            lower.startsWith("base64 ") ||
            lower.startsWith("md5sum ") ||
            lower.startsWith("sha256sum ") ||
            lower.startsWith("date ") ||
            lower.startsWith("cal ") ||
            lower.startsWith("bc ") ||
            lower.startsWith("sort ") ||
            lower.startsWith("uniq ") ||
            lower.startsWith("cut ") ||
            lower.startsWith("tr ") ||
            lower.startsWith("wc ") ||
            lower.startsWith("nl ") ||
            lower.startsWith("tac ") ||
            lower.startsWith("rev ") ||
            lower.startsWith("basename ") ||
            lower.startsWith("dirname ") ||
            lower.startsWith("realpath ") ||
            lower.startsWith("ln ") ||
            lower.startsWith("readlink ") ||
            lower.startsWith("file ") ||
            lower.startsWith("stat ") ||
            lower.startsWith("test ") ||
            lower.startsWith("[ ") ||
            lower.startsWith("[[ ") ||
            lower.startsWith("if ") ||
            lower.startsWith("for ") ||
            lower.startsWith("while ") ||
            lower.startsWith("case ") ||
            lower.startsWith("function ") ||
            lower.startsWith("return ") ||
            lower.startsWith("exit ") ||
            lower.startsWith("source ") ||
            lower.startsWith(". ") ||
            lower.startsWith("eval ") ||
            lower.startsWith("exec ") ||
            lower.startsWith("nohup ") ||
            lower.startsWith("screen ") ||
            lower.startsWith("tmux ") ||
            lower.startsWith("bg ") ||
            lower.startsWith("fg ") ||
            lower.startsWith("jobs ") ||
            lower.startsWith("disown ") ||
            lower.startsWith("wait ") ||
            lower.startsWith("sleep ") ||
            lower.startsWith("time ") ||
            lower.startsWith("nice ") ||
            lower.startsWith("renice ") ||
            lower.startsWith("nproc ") ||
            lower.startsWith("ulimit ") ||
            lower.startsWith("umask ") ||
            lower.startsWith("set ") ||
            lower.startsWith("unset ") ||
            lower.startsWith("alias ") ||
            lower.startsWith("unalias ") ||
            lower.startsWith("history ") ||
            lower.startsWith("help ") ||
            lower.startsWith("man ")) {
            return true;
        }
        
        // 检查是否包含典型的命令行特征（不以中文开头）
        // 1. 包含路径分隔符
        if (lower.contains("/") || lower.contains("\\")) {
            return true;
        }
        
        // 2. 包含命令管道或重定向
        if (lower.contains("|") || lower.contains(">") || lower.contains("<")) {
            return true;
        }
        
        // 3. 包含变量引用
        if (lower.contains("$") && lower.contains("{")) {
            return true;
        }
        
        // 4. 包含波浪号（家目录）
        if (lower.contains("~")) {
            return true;
        }
        
        // 5. 包含命令替换
        if (lower.contains("$(") || lower.contains("`")) {
            return true;
        }
        
        // 6. 包含后台运行符
        if (lower.endsWith("&")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 构建 LLM Prompt 用于生成 shell 命令
     */
    private String buildCommandGenerationPrompt(String query) {
        return String.format("""
            你是一个 Linux 系统管理专家。请将用户的自然语言请求转换为对应的 shell 命令。
            
            用户请求: %s
            
            要求:
            1. 只输出 shell 命令，不要解释
            2. 命令要安全、准确
            3. 如果请求不明确，返回空字符串
            4. 不要包含 markdown 代码块标记（如 ```bash）
            
            示例:
            - 用户请求: "查看当前目录的文件" -> 输出: ls -la
            - 用户请求: "删除桌面文件" -> 输出: rm -rf ~/Desktop/*
            - 用户请求: "查看内存使用情况" -> 输出: free -h
            - 用户请求: "查看系统进程" -> 输出: ps aux
            
            请输出对应的 shell 命令:
            """, query);
    }
    
    /**
     * 从 LLM 响应中提取 shell 命令
     */
    private String extractCommandFromLLMResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        
        // 去除 markdown 代码块标记
        response = response.replaceAll("```bash\\s*", "")
                          .replaceAll("```sh\\s*", "")
                          .replaceAll("```\\s*", "")
                          .trim();
        
        // 只取第一行（命令本身）
        String[] lines = response.split("\\n");
        if (lines.length > 0) {
            String command = lines[0].trim();
            // 移除可能的注释
            if (command.contains("#")) {
                command = command.substring(0, command.indexOf("#")).trim();
            }
            return command.isEmpty() ? null : command;
        }
        
        return null;
    }
    
    /**
     * 检查是否在黑名单
     * 同时检查正则匹配和危险关键词
     */
    private boolean isBlacklisted(String command) {
        // 1. 正则匹配检查
        boolean regexMatch = BLACKLIST.stream()
            .anyMatch(pattern -> pattern.matcher(command).matches());
        
        if (regexMatch) {
            log.warn("[ExecutionAgent] 命令命中黑名单正则: {}", command);
            return true;
        }
        
        // 2. 危险关键词检查（不区分大小写）
        String lowerCommand = command.toLowerCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (lowerCommand.contains(keyword.toLowerCase())) {
                log.warn("[ExecutionAgent] 命令包含危险关键词 '{}': {}", keyword, command);
                return true;
            }
        }
        
        // 3. 编码绕过检测
        if (containsEncodedCommand(command)) {
            log.warn("[ExecutionAgent] 命令包含编码绕过尝试: {}", command);
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查是否在白名单
     */
    private boolean isWhitelisted(String command) {
        return WHITELIST.stream()
            .anyMatch(pattern -> pattern.matcher(command).matches());
    }
    
    /**
     * 检测编码绕过命令
     * 防止通过编码、变量替换等方式绕过黑名单
     */
    private boolean containsEncodedCommand(String command) {
        // 检测十六进制编码
        if (command.matches(".*\\\\x[0-9a-fA-F]{2}.*")) {
            return true;
        }
        
        // 检测 base64 编码模式
        if (command.matches(".*[A-Za-z0-9+/]{20,}={0,2}.*")) {
            // 可能是 base64，但需要更精确的检测
            if (command.contains("$(") && command.contains("base64")) {
                return true;
            }
        }
        
        // 检测命令替换变量
        if (command.contains("${") && command.contains("}")) {
            // 允许基本的变量使用，但禁止某些危险变量
            if (command.matches(".*\\$\\{[^}]*PATH[^}]*\\}.*") ||
                command.matches(".*\\$\\{[^}]*HOME[^}]*\\}.*")) {
                return true;
            }
        }
        
        // 检测管道到危险命令
        if (command.matches(".*\\|\\s*bash.*") ||
            command.matches(".*\\|\\s*sh.*") ||
            command.matches(".*\\|\\s*python.*")) {
            return true;
        }
        
        return false;
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
     * 执行命令（公共方法，供外部调用）
     * 
     * @param command 要执行的命令
     * @param approved 是否已经过审批
     * @return AgentResult 包含命令执行结果
     */
    public AgentResult executeCommand(String command, boolean approved) {
        log.info("[ExecutionAgent] ┌─────────────────────────────────────────");
        log.info("[ExecutionAgent] │ 命令执行开始");
        log.info("[ExecutionAgent] │ 命令: '{}'", command);
        log.info("[ExecutionAgent] │ 已审批: {}", approved);
        log.info("[ExecutionAgent] │ 工作目录: {}", System.getProperty("user.home"));
        log.info("[ExecutionAgent] └─────────────────────────────────────────");
        
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[ExecutionAgent] [步骤1/6] 记录审计日志...");
            logAuditLog(command, approved);
            log.info("[ExecutionAgent] [步骤1/6] 审计日志记录完成");
            
            log.info("[ExecutionAgent] [步骤2/6] 构建 ProcessBuilder...");
            ProcessBuilder processBuilder = buildProcessBuilder(command);
            log.info("[ExecutionAgent] [步骤2/6] ProcessBuilder 构建完成: {}", processBuilder.command());
            
            log.info("[ExecutionAgent] [步骤3/6] 启动进程执行命令...");
            Process process = processBuilder.start();
            log.info("[ExecutionAgent] [步骤3/6] 进程启动成功, PID: {}", process.pid());
            
            log.info("[ExecutionAgent] [步骤4/6] 捕获命令输出...");
            CommandOutput output = captureProcessOutput(process);
            log.info("[ExecutionAgent] [步骤4/6] 输出捕获完成");
            log.debug("[ExecutionAgent] ┌──────────────── STDOUT ────────────────");
            log.debug("[ExecutionAgent] {}", output.stdout);
            log.debug("[ExecutionAgent] └─────────────────────────────────────────");
            if (!output.stderr.isEmpty()) {
                log.warn("[ExecutionAgent] ┌──────────────── STDERR ────────────────");
                log.warn("[ExecutionAgent] {}", output.stderr);
                log.warn("[ExecutionAgent] └─────────────────────────────────────────");
            }
            
            log.info("[ExecutionAgent] [步骤5/6] 等待进程结束, 超时时间: {}s", commandTimeoutSeconds);
            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            
            long duration = System.currentTimeMillis() - startTime;
            
            if (!finished) {
                log.warn("[ExecutionAgent] [步骤5/6] 命令执行超时! 强制终止进程");
                process.destroyForcibly();
                return AgentResult.builder()
                    .success(false)
                    .errorMessage(String.format("命令执行超时（超过 %d 秒）", commandTimeoutSeconds))
                    .response(output.stdout)
                    .executionTimeMs(duration)
                    .suggestedCommands(List.of(
                        AgentResult.CommandSuggestion.builder()
                            .command(command)
                            .description("重新执行（超时）")
                            .riskLevel("INFO")
                            .requiresApproval(false)
                            .build()
                    ))
                    .build();
            }
            
            int exitCode = process.exitValue();
            log.info("[ExecutionAgent] [步骤5/6] 进程结束, 退出码: {}", exitCode);
            
            log.info("[ExecutionAgent] [步骤6/6] 构建执行结果...");
            AgentResult result = AgentResult.builder()
                .success(exitCode == 0)
                .response(output.stdout)
                .executionTimeMs(duration)
                .build();
            
            if (exitCode != 0) {
                result.setErrorMessage(String.format("命令执行失败，退出码: %d", exitCode));
                // 添加命令建议，让用户可以再次尝试
                result.setSuggestedCommands(List.of(
                    AgentResult.CommandSuggestion.builder()
                        .command(command)
                        .description("重新执行命令")
                        .riskLevel("INFO")
                        .requiresApproval(false)
                        .build()
                ));
            }
            
            log.info("[ExecutionAgent] [步骤6/6] 执行结果构建完成");
            log.info("[ExecutionAgent] ┌─────────────────────────────────────────");
            log.info("[ExecutionAgent] │ 命令执行结束");
            log.info("[ExecutionAgent] │ 成功: {}", result.isSuccess());
            log.info("[ExecutionAgent] │ 耗时: {}ms", duration);
            log.info("[ExecutionAgent] │ 退出码: {}", exitCode);
            log.info("[ExecutionAgent] └─────────────────────────────────────────");
            
            return result;
            
        } catch (IOException e) {
            log.error("[ExecutionAgent] 命令执行 IO 异常: {}", e.getMessage(), e);
            String errorResponse = "命令执行失败: " + e.getMessage() + "\n\n可能的原因:\n- 容器环境中缺少必要的命令工具\n- 工作目录不存在\n- 权限不足\n\n建议检查系统环境配置或尝试其他命令。";
            return AgentResult.builder()
                .success(false)
                .response(errorResponse)
                .errorMessage("命令执行失败: " + e.getMessage())
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .suggestedCommands(List.of(
                    AgentResult.CommandSuggestion.builder()
                        .command(command)
                        .description("重新执行（IO异常）")
                        .riskLevel("INFO")
                        .requiresApproval(false)
                        .build()
                ))
                .build();
        } catch (InterruptedException e) {
            log.error("[ExecutionAgent] 命令执行被中断: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            String errorResponse = "命令执行被中断，请稍后重试。";
            return AgentResult.builder()
                .success(false)
                .response(errorResponse)
                .errorMessage("命令执行被中断")
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .suggestedCommands(List.of(
                    AgentResult.CommandSuggestion.builder()
                        .command(command)
                        .description("重新执行（中断）")
                        .riskLevel("INFO")
                        .requiresApproval(false)
                        .build()
                ))
                .build();
        } catch (Exception e) {
            log.error("[ExecutionAgent] 命令执行异常: {}", e.getMessage(), e);
            String errorResponse = "命令执行异常: " + e.getMessage() + "\n\n请检查命令格式是否正确，或联系管理员处理。";
            return AgentResult.builder()
                .success(false)
                .response(errorResponse)
                .errorMessage("命令执行异常: " + e.getMessage())
                .executionTimeMs(System.currentTimeMillis() - startTime)
                .suggestedCommands(List.of(
                    AgentResult.CommandSuggestion.builder()
                        .command(command)
                        .description("重新执行（异常）")
                        .riskLevel("INFO")
                        .requiresApproval(false)
                        .build()
                ))
                .build();
        }
    }
    
    /**
     * 构建 ProcessBuilder
     */
    private ProcessBuilder buildProcessBuilder(String command) {
        ProcessBuilder processBuilder = new ProcessBuilder();
        
        // Windows 和 Unix 系统使用不同的 shell
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            // 使用 sh 而不是 bash，因为 Alpine Linux 容器中默认没有 bash
            processBuilder.command("sh", "-c", command);
        }
        
        // 设置工作目录
        processBuilder.directory(new java.io.File(System.getProperty("user.home")));
        
        // 合并错误流到标准输出
        processBuilder.redirectErrorStream(false);
        
        return processBuilder;
    }
    
    /**
     * 捕获进程输出
     */
    private CommandOutput captureProcessOutput(Process process) throws IOException {
        CommandOutput output = new CommandOutput();
        
        try (BufferedReader stdoutReader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader stderrReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            
            // 读取标准输出
            String line;
            int lineCount = 0;
            int byteCount = 0;
            StringBuilder stdoutBuilder = new StringBuilder();
            while ((line = stdoutReader.readLine()) != null) {
                if (lineCount >= maxOutputLines || byteCount >= maxOutputBytes) {
                    stdoutBuilder.append("\n... (输出被截断)");
                    break;
                }
                stdoutBuilder.append(line).append("\n");
                lineCount++;
                byteCount += line.getBytes(StandardCharsets.UTF_8).length + 1;
            }
            output.stdout = stdoutBuilder.toString();
            
            // 读取错误输出
            StringBuilder stderrBuilder = new StringBuilder();
            while ((line = stderrReader.readLine()) != null) {
                stderrBuilder.append(line).append("\n");
            }
            output.stderr = stderrBuilder.toString();
        }
        
        return output;
    }
    
    /**
     * 记录审计日志
     */
    private void logAuditLog(String command, boolean approved) {
        String traceId = MDC.get("traceId");
        String userId = MDC.get("userId");
        
        log.info("[ExecutionAgent] [审计] traceId={}, userId={}, command={}, approved={}", 
            traceId, userId, command, approved);
        
        // TODO: 将审计日志持久化到数据库
    }
    
    /**
     * 创建审批请求
     */
    private AgentResult createApprovalRequest(String command, RiskAssessment assessment) {
        log.info("创建审批请求: {}, 风险: {}", command, assessment.getLevel());
        
        String approvalId = UUID.randomUUID().toString();
        ApprovalRequest request = ApprovalRequest.builder()
            .approvalId(approvalId)
            .command(command)
            .riskLevel(assessment.getLevel())
            .riskScore(assessment.getScore())
            .userId(MDC.get("userId"))
            .status(AgentStateManager.ApprovalStatus.PENDING)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(1800))  // 30 分钟后过期
            .build();
        
        agentStateManager.createApprovalRequest(request);
        
        // 发布审批请求事件
        agentEventBus.publish(AgentMessage.builder()
            .type(AgentMessage.MessageType.APPROVAL_REQUEST)
            .sourceAgent("ExecutionAgent")
            .payload(Map.of(
                "approvalId", approvalId,
                "command", command,
                "riskLevel", assessment.getLevel(),
                "riskScore", assessment.getScore()
            ))
            .build());
        
        log.info("[ExecutionAgent] 创建审批请求: approvalId={}, command={}, riskLevel={}", 
            approvalId, command, assessment.getLevel());
        
        return AgentResult.builder()
            .success(true)
            .response(String.format("命令 '%s' 需要审批，风险等级: %s，审批ID: %s", command, assessment.getLevel(), approvalId))
            .suggestedCommands(List.of(
                AgentResult.CommandSuggestion.builder()
                    .command(command)
                    .description(String.format("风险等级: %s", assessment.getLevel()))
                    .riskLevel(assessment.getLevel())
                    .requiresApproval(true)
                    .build()
            ))
            .build();
    }
    
    /**
     * 命令输出封装
     */
    private static class CommandOutput {
        String stdout = "";
        String stderr = "";
    }
    
    /**
     * 风险评估结果
     */
    private static class RiskAssessment {
        private final int score;
        private final String level;
        
        public RiskAssessment(int score, String level) {
            this.score = score;
            this.level = level;
        }
        
        public int getScore() {
            return score;
        }
        
        public String getLevel() {
            return level;
        }
    }
}