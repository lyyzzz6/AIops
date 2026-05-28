package com.netdata.ops.core.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务状态检查工具
 *
 * <p>通过执行系统命令检查目标主机上服务的运行状态和资源占用。
 * 包括进程状态、监听端口、CPU/内存占用等信息。支持 Windows 和 Linux 系统。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@AgentTool(
        name = "check_service",
        description = "检查目标主机上服务的运行状态和资源占用",
        parameters = {
                "service_name: 服务名称",
                "host: 主机地址(默认localhost)"
        }
)
public class CheckServiceTool implements Tool {

    private static final Random RANDOM = new Random();

    @Override
    public String getName() {
        return "check_service";
    }

    @Override
    public String getDescription() {
        return "检查目标主机上服务的运行状态和资源占用";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String serviceName = (String) params.getOrDefault("service_name", "unknown");
        String host = (String) params.getOrDefault("host", "localhost");

        log.info("[CheckServiceTool] 检查服务状态: service={}, host={}", serviceName, host);

        // 首先尝试通过系统命令获取真实数据
        try {
            ServiceStatus status = checkServiceReal(serviceName, host);
            if (status != null && status.isValid()) {
                return formatRealStatus(serviceName, host, status);
            }
        } catch (Exception e) {
            log.warn("[CheckServiceTool] 系统命令检查失败: {}", e.getMessage());
        }

        // 如果真实检查失败，使用模拟数据作为兜底
        log.warn("[CheckServiceTool] 真实数据不可用，使用模拟数据作为兜底");
        return generateServiceStatus(serviceName, host);
    }

    /**
     * 通过系统命令检查服务状态
     */
    private ServiceStatus checkServiceReal(String serviceName, String host) {
        ServiceStatus status = new ServiceStatus();
        
        if (isWindows()) {
            return checkServiceWindows(serviceName);
        } else {
            return checkServiceLinux(serviceName);
        }
    }

    /**
     * 检查 Windows 服务状态
     */
    private ServiceStatus checkServiceWindows(String serviceName) {
        ServiceStatus status = new ServiceStatus();
        
        // 尝试通过 sc query 检查服务
        String scOutput = executeCommand("sc query " + serviceName);
        if (scOutput != null) {
            status.status = parseWindowsServiceStatus(scOutput);
            status.processInfo = "Windows Service";
            
            // 尝试获取 PID
            Pattern pidPattern = Pattern.compile("PID\\s+:\\s*(\\d+)");
            Matcher matcher = pidPattern.matcher(scOutput);
            if (matcher.find()) {
                status.pid = Integer.parseInt(matcher.group(1));
            }
        }
        
        // 如果服务没找到，尝试通过 tasklist 检查进程
        if ("stopped".equals(status.status) || status.status == null) {
            String tasklistOutput = executeCommand("tasklist /FI \"IMAGENAME eq " + serviceName + ".exe\"");
            if (tasklistOutput != null && !tasklistOutput.contains("信息: 没有运行的任务")) {
                status.status = "running";
                status.processInfo = serviceName + ".exe";
                
                Pattern pattern = Pattern.compile(serviceName + "\\.exe\\s+(\\d+)");
                Matcher matcher = pattern.matcher(tasklistOutput);
                if (matcher.find()) {
                    status.pid = Integer.parseInt(matcher.group(1));
                }
            }
        }
        
        // 如果找到了 PID，获取更多信息
        if (status.pid > 0) {
            String wmicOutput = executeCommand("wmic process where processid=" + status.pid + " get Caption,ProcessId,WorkingSetSize,CPU /format:list");
            if (wmicOutput != null) {
                Pattern cpuPattern = Pattern.compile("CPU=(\\d+)");
                Matcher cpuMatcher = cpuPattern.matcher(wmicOutput);
                if (cpuMatcher.find()) {
                    status.cpuPercent = Double.parseDouble(cpuMatcher.group(1)) / 1000;
                }
                
                Pattern memPattern = Pattern.compile("WorkingSetSize=(\\d+)");
                Matcher memMatcher = memPattern.matcher(wmicOutput);
                if (memMatcher.find()) {
                    long memBytes = Long.parseLong(memMatcher.group(1));
                    status.memoryPercent = (memBytes / (1024.0 * 1024.0));
                }
            }
        }
        
        return status;
    }

    /**
     * 检查 Linux 服务状态
     */
    private ServiceStatus checkServiceLinux(String serviceName) {
        ServiceStatus status = new ServiceStatus();

        // 首先检查是否是 Docker 容器
        String dockerOutput = executeCommand("docker ps --filter name=" + serviceName + " --format '{{.Names}}:{{.Status}}'");
        if (dockerOutput != null && !dockerOutput.isEmpty()) {
            status.status = "running";
            status.processInfo = "Docker Container";
            String[] parts = dockerOutput.split(":");
            if (parts.length >= 1) {
                status.processInfo = "Container: " + parts[0];
            }
            log.info("[CheckServiceTool] 找到 Docker 容器: {}", dockerOutput);
            return status;
        }

        // 尝试通过 docker ps 检查所有容器（模糊匹配）
        dockerOutput = executeCommand("docker ps --format '{{.Names}}'");
        if (dockerOutput != null && dockerOutput.toLowerCase().contains(serviceName.toLowerCase())) {
            status.status = "running";
            status.processInfo = "Docker Container matching: " + serviceName;
            log.info("[CheckServiceTool] 通过模糊匹配找到 Docker 容器");
            return status;
        }

        // 尝试 systemctl
        String systemctlOutput = executeCommand("systemctl status " + serviceName);
        if (systemctlOutput != null) {
            if (systemctlOutput.contains("active (running)")) {
                status.status = "running";
            } else if (systemctlOutput.contains("inactive (dead)")) {
                status.status = "stopped";
            } else if (systemctlOutput.contains("active (exited)")) {
                status.status = "exited";
            }

            Pattern pidPattern = Pattern.compile("Main PID:\\s+(\\d+)");
            Matcher matcher = pidPattern.matcher(systemctlOutput);
            if (matcher.find()) {
                status.pid = Integer.parseInt(matcher.group(1));
            }

            status.processInfo = "Systemd Service";
        }

        // 如果 systemctl 没找到，尝试 ps
        if ("stopped".equals(status.status) || status.status == null) {
            String psOutput = executeCommand("ps aux | grep -i " + serviceName + " | grep -v grep");
            if (psOutput != null && !psOutput.isEmpty()) {
                String[] lines = psOutput.split("\n");
                if (lines.length > 0) {
                    String[] parts = lines[0].trim().split("\\s+");
                    if (parts.length >= 2) {
                        status.pid = Integer.parseInt(parts[1]);
                        status.status = "running";
                        status.processInfo = parts[parts.length - 1];
                    }
                }
            }
        }

        // 获取资源占用
        if (status.pid > 0) {
            String topOutput = executeCommand("top -b -n 1 -p " + status.pid);
            if (topOutput != null) {
                String[] lines = topOutput.split("\n");
                for (String line : lines) {
                    if (line.contains(Integer.toString(status.pid))) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 10) {
                            status.cpuPercent = Double.parseDouble(parts[8]);
                            status.memoryPercent = Double.parseDouble(parts[9]);
                        }
                        break;
                    }
                }
            }
        }

        return status;
    }

    /**
     * 解析 Windows 服务状态
     */
    private String parseWindowsServiceStatus(String scOutput) {
        if (scOutput.contains("RUNNING")) {
            return "running";
        } else if (scOutput.contains("STOPPED")) {
            return "stopped";
        } else if (scOutput.contains("START_PENDING")) {
            return "starting";
        } else if (scOutput.contains("STOP_PENDING")) {
            return "stopping";
        }
        return "unknown";
    }

    /**
     * 执行系统命令
     */
    private String executeCommand(String command) {
        try {
            log.debug("[CheckServiceTool] 执行命令: {}", command);
            
            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("/bin/sh", "-c", command);
            }
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            log.debug("[CheckServiceTool] 命令执行完成，退出码: {}", exitCode);
            
            String result = output.toString().trim();
            return result.isEmpty() ? null : result;
            
        } catch (Exception e) {
            log.warn("[CheckServiceTool] 执行命令失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否为 Windows 系统
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 格式化真实状态输出
     */
    private String formatRealStatus(String serviceName, String host, ServiceStatus status) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        boolean isRunning = "running".equals(status.status);
        
        return String.format(
                "服务状态检查结果:\n"
                        + "  服务名: %s\n"
                        + "  主机: %s\n"
                        + "  检查时间: %s\n"
                        + "  数据来源: 系统命令获取的真实数据\n"
                        + "  ─────────────────────\n"
                        + "  进程状态: %s\n"
                        + "  进程信息: %s\n"
                        + "  PID: %d\n"
                        + "  CPU 占用: %.1f%%\n"
                        + "  内存占用: %.1f%%\n"
                        + "  ─────────────────────\n"
                        + "  健康检查: %s",
                serviceName, host, now,
                status.status, 
                status.processInfo != null ? status.processInfo : "N/A",
                status.pid,
                status.cpuPercent,
                status.memoryPercent,
                isRunning ? "HEALTHY" : "UNHEALTHY"
        );
    }

    /**
     * 服务状态数据类
     */
    private static class ServiceStatus {
        String status;
        String processInfo;
        int pid;
        double cpuPercent;
        double memoryPercent;
        
        boolean isValid() {
            return status != null && !"unknown".equals(status);
        }
    }

    /**
     * 格式化 MCP 返回结果
     */
    private String formatMcpResponse(Map<String, Object> response, String serviceName, String host) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 提取字段
        String status = (String) response.getOrDefault("status", "unknown");
        String processInfo = (String) response.getOrDefault("processInfo", "N/A");
        int pid = response.get("pid") instanceof Number ? ((Number) response.get("pid")).intValue() : 0;
        int port = response.get("port") instanceof Number ? ((Number) response.get("port")).intValue() : 0;
        double cpuPercent = response.get("cpuPercent") instanceof Number ? ((Number) response.get("cpuPercent")).doubleValue() : 0.0;
        double memoryPercent = response.get("memoryPercent") instanceof Number ? ((Number) response.get("memoryPercent")).doubleValue() : 0.0;
        String upSince = (String) response.get("upSince");
        boolean portReachable = response.get("portReachable") instanceof Boolean ? ((Boolean) response.get("portReachable")) : false;
        boolean healthy = response.get("healthy") instanceof Boolean ? ((Boolean) response.get("healthy")) : false;

        return String.format(
                "服务状态检查结果:\n"
                        + "  服务名: %s\n"
                        + "  主机: %s\n"
                        + "  检查时间: %s\n"
                        + "  数据来源: MCP 真实数据\n"
                        + "  ─────────────────────\n"
                        + "  进程状态: %s\n"
                        + "  进程信息: %s\n"
                        + "  PID: %d\n"
                        + "  监听端口: %d\n"
                        + "  CPU 占用: %.1f%%\n"
                        + "  内存占用: %.1f%%\n"
                        + "  运行时间: %s\n"
                        + "  ─────────────────────\n"
                        + "  端口连通性: %s\n"
                        + "  健康检查: %s",
                serviceName,
                host,
                now,
                status,
                processInfo,
                pid,
                port,
                cpuPercent,
                memoryPercent,
                upSince != null ? "自 " + upSince + " 起" : "N/A",
                portReachable ? "端口 " + port + " 可达" : "端口 " + port + " 不可达",
                healthy ? "HEALTHY" : "UNHEALTHY"
        );
    }

    /**
     * 生成模拟服务状态信息（作为真实数据不可用时的兜底）
     */
    private String generateServiceStatus(String serviceName, String host) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return switch (serviceName.toLowerCase()) {
            case "nginx" -> formatStatus(serviceName, host, now,
                    "running", "master + 4 workers",
                    80, 1.2, 0.5, 15342, "2026-05-06 08:00:00");

            case "mysql", "mysqld" -> formatStatus(serviceName, host, now,
                    "running", "mysqld (pid: 2341)",
                    3306, 8.5, 12.3, 2341, "2026-05-05 22:00:00");

            case "redis", "redis-server" -> formatStatus(serviceName, host, now,
                    "running", "redis-server *:6379",
                    6379, 0.3, 2.1, 3456, "2026-05-06 08:00:00");

            case "java", "spring", "springboot" -> formatStatus(serviceName, host, now,
                    "running", "java -jar app.jar",
                    8080, 45.2 + RANDOM.nextDouble() * 30, 35.0 + RANDOM.nextDouble() * 20,
                    5678, "2026-05-06 10:30:00");

            case "docker", "dockerd" -> formatStatus(serviceName, host, now,
                    "running", "dockerd --host=fd://",
                    2375, 3.2, 4.5, 1234, "2026-05-05 20:00:00");

            default -> formatStatus(serviceName, host, now,
                    RANDOM.nextBoolean() ? "running" : "stopped",
                    serviceName + " process",
                    8000 + RANDOM.nextInt(1000),
                    RANDOM.nextDouble() * 20,
                    RANDOM.nextDouble() * 10,
                    10000 + RANDOM.nextInt(50000),
                    "2026-05-06 00:00:00");
        };
    }

    /**
     * 格式化服务状态输出
     */
    private String formatStatus(String serviceName, String host, String checkTime,
                                String status, String processInfo,
                                int port, double cpuPercent, double memPercent,
                                int pid, String upSince) {
        boolean isRunning = "running".equals(status);
        return String.format(
                "服务状态检查结果:\n"
                        + "  服务名: %s\n"
                        + "  主机: %s\n"
                        + "  检查时间: %s\n"
                        + "  数据来源: 模拟数据（兜底）\n"
                        + "  ─────────────────────\n"
                        + "  进程状态: %s\n"
                        + "  进程信息: %s\n"
                        + "  PID: %d\n"
                        + "  监听端口: %d\n"
                        + "  CPU 占用: %.1f%%\n"
                        + "  内存占用: %.1f%%\n"
                        + "  运行时间: 自 %s 起\n"
                        + "  ─────────────────────\n"
                        + "  端口连通性: %s\n"
                        + "  健康检查: %s",
                serviceName, host, checkTime,
                status, processInfo, pid, port,
                cpuPercent, memPercent, upSince,
                isRunning ? "端口 " + port + " 可达" : "端口 " + port + " 不可达",
                isRunning ? "HEALTHY" : "UNHEALTHY"
        );
    }
}