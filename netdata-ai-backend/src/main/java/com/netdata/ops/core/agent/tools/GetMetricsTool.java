package com.netdata.ops.core.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

/**
 * 获取监控指标工具
 *
 * <p>从 NetData 监控系统获取历史指标数据，支持 CPU、内存、磁盘、网络等指标。
 * 当前为模拟实现，后续可对接 NetData REST API。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@AgentTool(
        name = "get_metrics",
        description = "获取 NetData 监控指标的历史数据。可查询 CPU、内存、磁盘、网络等指标的时间序列数据。",
        parameters = {
                "metric_name: 指标名称(cpu/memory/disk/network)",
                "time_range: 时间范围(1h/6h/24h/7d)"
        }
)
public class GetMetricsTool implements Tool {

    private static final Random RANDOM = new Random();

    @Override
    public String getName() {
        return "get_metrics";
    }

    @Override
    public String getDescription() {
        return "获取 NetData 监控指标的历史数据";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String metricName = (String) params.getOrDefault("metric_name", "cpu");
        String timeRange = (String) params.getOrDefault("time_range", "1h");

        log.info("[GetMetricsTool] 获取指标数据: metric={}, range={}", metricName, timeRange);

        try {
            // 模拟从 NetData API 获取数据
            return generateMetricsData(metricName, timeRange);
        } catch (Exception e) {
            log.error("[GetMetricsTool] 获取指标失败: {}", e.getMessage(), e);
            return "获取指标数据失败: " + e.getMessage();
        }
    }

    /**
     * 模拟生成指标数据（后续替换为真实 NetData API 调用）
     */
    private String generateMetricsData(String metricName, String timeRange) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        return switch (metricName.toLowerCase()) {
            case "cpu" -> String.format(
                    "指标: CPU 使用率\n时间范围: 最近%s\n"
                            + "当前值: %.1f%%\n平均值: %.1f%%\n最大值: %.1f%%\n最小值: %.1f%%\n"
                            + "趋势: 过去%s内从 %.1f%% 上升到 %.1f%%，在 %s 出现峰值 %.1f%%",
                    timeRange,
                    60 + RANDOM.nextDouble() * 35,
                    45 + RANDOM.nextDouble() * 20,
                    85 + RANDOM.nextDouble() * 15,
                    10 + RANDOM.nextDouble() * 20,
                    timeRange,
                    20 + RANDOM.nextDouble() * 15,
                    75 + RANDOM.nextDouble() * 20,
                    now,
                    90 + RANDOM.nextDouble() * 10);

            case "memory" -> String.format(
                    "指标: 内存使用率\n时间范围: 最近%s\n"
                            + "当前值: %.1f%%\n已用: %.1fGB / 总计: 16GB\n"
                            + "缓存: %.1fGB\n交换区使用: %.1f%%\n"
                            + "趋势: 内存使用率缓慢上升，疑似内存泄漏",
                    timeRange,
                    70 + RANDOM.nextDouble() * 20,
                    11 + RANDOM.nextDouble() * 4,
                    2 + RANDOM.nextDouble() * 3,
                    RANDOM.nextDouble() * 30);

            case "disk" -> String.format(
                    "指标: 磁盘使用情况\n时间范围: 最近%s\n"
                            + "/ 分区使用率: %.1f%%\n/data 分区使用率: %.1f%%\n"
                            + "磁盘 I/O 读: %.1f MB/s\n磁盘 I/O 写: %.1f MB/s\n"
                            + "inode 使用率: %.1f%%",
                    timeRange,
                    50 + RANDOM.nextDouble() * 30,
                    60 + RANDOM.nextDouble() * 30,
                    RANDOM.nextDouble() * 100,
                    RANDOM.nextDouble() * 80,
                    RANDOM.nextDouble() * 50);

            case "network" -> String.format(
                    "指标: 网络流量\n时间范围: 最近%s\n"
                            + "入站流量: %.1f Mbps\n出站流量: %.1f Mbps\n"
                            + "丢包率: %.2f%%\n延迟: %.1fms\n"
                            + "TCP 连接数: %d\n重传率: %.2f%%",
                    timeRange,
                    RANDOM.nextDouble() * 500,
                    RANDOM.nextDouble() * 200,
                    RANDOM.nextDouble() * 2,
                    RANDOM.nextDouble() * 50,
                    100 + RANDOM.nextInt(5000),
                    RANDOM.nextDouble() * 5);

            default -> String.format("未知指标类型: %s。支持的指标: cpu, memory, disk, network", metricName);
        };
    }
}
