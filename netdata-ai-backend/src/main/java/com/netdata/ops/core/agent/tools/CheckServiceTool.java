package com.netdata.ops.core.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

/**
 * 服务状态检查工具
 *
 * <p>检查目标主机上服务的运行状态和资源占用，
 * 包括进程状态、监听端口、CPU/内存占用等信息。
 * 当前为模拟实现，后续可对接实际的服务探测接口。
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

        try {
            return generateServiceStatus(serviceName, host);
        } catch (Exception e) {
            log.error("[CheckServiceTool] 检查服务状态失败: {}", e.getMessage(), e);
            return "检查服务状态失败: " + e.getMessage();
        }
    }

    /**
     * 模拟生成服务状态信息
     */
    private String generateServiceStatus(String serviceName, String host) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 根据服务名模拟不同的状态
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
        return String.format(
                "服务状态检查结果:\n"
                        + "  服务名: %s\n"
                        + "  主机: %s\n"
                        + "  检查时间: %s\n"
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
                "running".equals(status) ? "端口 " + port + " 可达" : "端口 " + port + " 不可达",
                "running".equals(status) ? "HEALTHY" : "UNHEALTHY"
        );
    }
}
