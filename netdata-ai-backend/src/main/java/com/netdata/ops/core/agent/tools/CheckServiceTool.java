package com.netdata.ops.core.agent.tools;

import com.netdata.ops.core.agent.client.NetDataClient;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务状态检查工具
 *
 * <p>首先尝试通过 NetData 获取真实监控数据，失败时降级为系统命令检查。
 * 包括进程状态、监听端口、CPU/内存占用等信息。支持 Windows 和 Linux 系统。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@RequiredArgsConstructor
@AgentTool(
        name = "check_service",
        description = "检查目标主机上服务的运行状态和资源占用",
        parameters = {
                "service_name: 服务名称",
                "host: 主机地址(默认localhost)"
        }
)
public class CheckServiceTool implements Tool {

    private final NetDataClient netDataClient;

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

        // 首先尝试通过 NetData 获取真实监控数据
        try {
            String netDataResult = checkWithNetData(serviceName, host);
            if (netDataResult != null) {
                return netDataResult;
            }
        } catch (Exception e) {
            log.warn("[CheckServiceTool] NetData 检查失败: {}", e.getMessage());
        }

        // NetData 失败，降级为系统命令检查
        try {
            ServiceStatus status = checkServiceReal(serviceName, host);
            if (status != null && status.isValid()) {
                return formatRealStatus(serviceName, host, status);
            }
        } catch (Exception e) {
            log.warn("[CheckServiceTool] 系统命令检查失败: {}", e.getMessage());
        }

        // 都失败，返回明确错误
        return String.format(
                "服务状态检查失败: 无法通过 NetData 或系统命令获取 '%s' 的状态信息。" +
                        "\n请确认：\n1. 服务名称正确\n2. 目标主机可访问\n3. 有足够的权限执行命令",
                serviceName);
    }

    /**
     * 通过 NetData 检查服务状态
     */
    private String checkWithNetData(String serviceName, String host) {
        log.info("[CheckServiceTool] 尝试通过 NetData 检查: {}", serviceName);

        // 尝试获取与服务相关的指标
        Map<String, Object> cpuMetrics = netDataClient.getMetrics("cpu", "1h");
        Map<String, Object> memoryMetrics = netDataClient.getMetrics("memory", "1h");

        // 获取系统信息
        Map<String, Object> systemInfo = netDataClient.getSystemInfo();
        Map<String, Object> charts = netDataClient.listCharts();

        // 格式化 NetData 检查结果
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();
        sb.append("服务状态检查结果:\n");
        sb.append(String.format("  服务名: %s\n", serviceName));
        sb.append(String.format("  主机: %s\n", host));
        sb.append(String.format("  检查时间: %s\n", now));
        sb.append("  数据来源: NetData 监控系统\n");
        sb.append("  ─────────────────────\n");

        // 添加系统指标信息
        if (cpuMetrics != null) {
            Double currentCpu = (Double) cpuMetrics.get("currentValue");
            sb.append(String.format("  系统 CPU: %.1f%%\n", currentCpu != null ? currentCpu : 0.0));
        }
        if (memoryMetrics != null) {
            Double currentMem = (Double) memoryMetrics.get("currentValue");
            sb.append(String.format("  系统内存: %.1f%%\n", currentMem != null ? currentMem : 0.0));
        }

        // 尝试通过图表名称匹配服务
        boolean serviceFound = false;
        if (charts != null && charts.containsKey("charts")) {
            Map<?, ?> chartsMap = (Map<?, ?>) charts.get("charts");
            for (Object chartKey : chartsMap.keySet()) {
                String chartName = String.valueOf(chartKey);
                if (chartName.toLowerCase().contains(serviceName.toLowerCase())) {
                    serviceFound = true;
                    sb.append(String.format("  匹配图表: %s\n", chartName));
                    break;
                }
            }
        }

        sb.append("  ─────────────────────\n");
        sb.append(String.format("  服务健康状态: %s\n", serviceFound ? "正常" : "未找到直接指标"));

        return sb.toString();
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

}