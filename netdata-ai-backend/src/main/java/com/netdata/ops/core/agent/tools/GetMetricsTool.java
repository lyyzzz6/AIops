package com.netdata.ops.core.agent.tools;

import com.netdata.ops.config.DataSourceProperties;
import com.netdata.ops.config.NetDataMcpConfig;
import com.netdata.ops.core.agent.client.McpToolClient;
import com.netdata.ops.core.agent.client.NetDataMcpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

/**
 * 获取监控指标工具
 *
 * <p>通过 MCP (Model Context Protocol) 调用 NetData 监控系统获取指标数据。
 * 支持 CPU、内存、磁盘、网络等指标。当 MCP 服务不可用时，自动回退到模拟数据。
 *
 * <p>数据源优先级：
 * 1. NetData 官方 MCP（如果启用）
 * 2. 自定义 MCP 客户端
 * 3. 模拟数据兜底
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@AgentTool(
        name = "get_metrics",
        description = "[已废弃] 请优先使用 netdata_list_metrics 和 netdata_query_metrics 工具。旧工具：获取 NetData 监控指标的历史数据。可查询 CPU、内存、磁盘、网络等指标的时间序列数据。",
        parameters = {
                "metric_name: 指标名称(cpu/memory/disk/network)",
                "time_range: 时间范围(1h/6h/24h/7d)"
        }
)
public class GetMetricsTool implements Tool {

    private static final Random RANDOM = new Random();

    private final McpToolClient mcpToolClient;
    private final NetDataMcpClient netDataMcpClient;
    private final DataSourceProperties dataSourceProperties;
    private final NetDataMcpConfig netDataMcpConfig;

    public GetMetricsTool(McpToolClient mcpToolClient, NetDataMcpClient netDataMcpClient,
                         DataSourceProperties dataSourceProperties, NetDataMcpConfig netDataMcpConfig) {
        this.mcpToolClient = mcpToolClient;
        this.netDataMcpClient = netDataMcpClient;
        this.dataSourceProperties = dataSourceProperties;
        this.netDataMcpConfig = netDataMcpConfig;
    }

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
        
        // 提示 LLM 使用新工具
        String newToolHint = """
            【重要提示】此工具已废弃！
            
            请使用新的工具链获取更准确的数据：
            1. 首先调用 netdata_list_metrics(pattern='system.ram'|'system.cpu') 获取可用的指标和维度
            2. 然后调用 netdata_query_metrics(metric='...', dimensions='...', after='-1h') 查询具体数据
            
            这样可以获得更完整、更准确的监控数据！
            """;

        // 优先尝试通过 NetData 官方 MCP 获取真实数据
        if (netDataMcpConfig.isEnabled()) {
            try {
                Map<String, Object> response = netDataMcpClient.getMetrics(metricName, timeRange);
                if (response != null && !response.isEmpty()) {
                    log.info("[GetMetricsTool] 通过 NetData MCP 获取真实数据成功");
                    return formatResponse(response, metricName, timeRange, true, "NetData 官方 MCP") 
                           + "\n\n" + newToolHint;
                }
                log.warn("[GetMetricsTool] NetData MCP 返回空，尝试其他数据源");
            } catch (Exception e) {
                log.warn("[GetMetricsTool] NetData MCP 调用失败，尝试其他数据源: {}", e.getMessage());
            }
        }

        // 尝试通过自定义 MCP 获取真实数据
        if (dataSourceProperties.isRealDataEnabled()) {
            try {
                Map<String, Object> response = mcpToolClient.getMetrics(metricName, timeRange);
                if (response != null && !response.isEmpty()) {
                    log.info("[GetMetricsTool] 通过自定义 MCP 获取真实数据成功");
                    return formatResponse(response, metricName, timeRange, true, "自定义 MCP");
                }
                log.warn("[GetMetricsTool] 自定义 MCP 返回空，使用模拟数据兜底");
            } catch (Exception e) {
                log.warn("[GetMetricsTool] 自定义 MCP 调用失败，使用模拟数据兜底: {}", e.getMessage());
            }
        }

        // 所有真实数据源都不可用，使用模拟数据作为兜底
        log.warn("[GetMetricsTool] 所有真实数据源都不可用，使用模拟数据作为兜底");
        return generateMetricsData(metricName, timeRange) + "\n\n" + newToolHint;
    }

    /**
     * 格式化响应结果
     */
    private String formatResponse(Map<String, Object> response, String metricName, 
                                  String timeRange, boolean isRealData, String dataSource) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String metricDisplayName = mapMetricDisplayName(metricName);

        StringBuilder sb = new StringBuilder();
        sb.append("指标: ").append(metricDisplayName).append("\n");
        sb.append("时间范围: 最近").append(timeRange).append("\n");
        sb.append("数据来源: ").append(isRealData ? dataSource + "（真实数据）" : "模拟数据（兜底）").append("\n");
        
        // 提取数值类型的字段
        Object currentValue = response.get("currentValue");
        Object average = response.get("average");
        Object max = response.get("max");
        Object min = response.get("min");
        
        if (currentValue instanceof Number) {
            sb.append(String.format("当前值: %.1f%%\n", ((Number) currentValue).doubleValue()));
        }
        if (average instanceof Number) {
            sb.append(String.format("平均值: %.1f%%\n", ((Number) average).doubleValue()));
        }
        if (max instanceof Number) {
            sb.append(String.format("最大值: %.1f%%\n", ((Number) max).doubleValue()));
        }
        if (min instanceof Number) {
            sb.append(String.format("最小值: %.1f%%\n", ((Number) min).doubleValue()));
        }
        
        sb.append("数据时间: ").append(now).append("\n");

        // 如果有额外标签信息
        if (response.containsKey("labels") && response.get("labels") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) response.get("labels");
            sb.append("标签信息:\n");
            labels.forEach((key, value) -> sb.append("  - ").append(key).append(": ").append(value).append("\n"));
        }

        return sb.toString();
    }

    /**
     * 将指标名称映射为显示名称
     */
    private String mapMetricDisplayName(String metricName) {
        return switch (metricName.toLowerCase()) {
            case "cpu" -> "CPU 使用率";
            case "memory" -> "内存使用率";
            case "disk" -> "磁盘使用情况";
            case "network" -> "网络流量";
            default -> metricName;
        };
    }

    /**
     * 生成模拟指标数据（作为真实数据不可用时的兜底）
     */
    private String generateMetricsData(String metricName, String timeRange) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        return switch (metricName.toLowerCase()) {
            case "cpu" -> String.format(
                    "指标: CPU 使用率\n时间范围: 最近%s\n"
                            + "数据来源: 模拟数据（兜底）\n"
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
                            + "数据来源: 模拟数据（兜底）\n"
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
                            + "数据来源: 模拟数据（兜底）\n"
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
                            + "数据来源: 模拟数据（兜底）\n"
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