package com.netdata.ops.core.agent.tools;

import com.netdata.ops.config.DataSourceProperties;
import com.netdata.ops.core.agent.client.NetDataClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常检测工具
 *
 * <p>直接从 NetData 获取真实的时间序列数据，使用统计方法（Z-score）进行异常检测分析。
 * 支持检测指标数据中的异常波动点。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@AgentTool(
        name = "detect_anomaly",
        description = "对监控指标进行异常检测。使用 Z-score 统计方法分析真实的 NetData 时间序列数据，检测异常波动点。",
        parameters = {
                "metric_name: 指标名称（如 memory.used, cpu.usage）",
                "threshold: 异常阈值(0-1)，建议 0.8"
        }
)
public class DetectAnomalyTool implements Tool {

    private final NetDataClient netDataClient;
    private final DataSourceProperties dataSourceProperties;

    public DetectAnomalyTool(NetDataClient netDataClient, DataSourceProperties dataSourceProperties) {
        this.netDataClient = netDataClient;
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public String getName() {
        return "detect_anomaly";
    }

    @Override
    public String getDescription() {
        return "对监控指标进行异常检测，使用真实的 NetData 数据进行统计分析";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String metricName = (String) params.getOrDefault("metric_name", "memory.used");
        double threshold = parseThreshold(params.getOrDefault("threshold", 0.8));

        log.info("[DetectAnomalyTool] 执行异常检测: metric={}, threshold={}", metricName, threshold);

        // 从 NetData 获取真实的时间序列数据
        Map<String, Object> timeSeriesData = fetchTimeSeriesData(metricName);
        
        if (timeSeriesData == null || !timeSeriesData.containsKey("data")) {
            log.warn("[DetectAnomalyTool] 无法获取指标数据: {}", metricName);
            return String.format("错误: 无法获取指标 '%s' 的数据。请确保 NetData 服务正常运行且指标名称正确。", metricName);
        }

        // 执行真实的异常检测
        return performAnomalyDetection(metricName, threshold, timeSeriesData);
    }

    /**
     * 从 NetData 获取时间序列数据
     */
    private Map<String, Object> fetchTimeSeriesData(String metricName) {
        try {
            // 从指标名称中提取 chart 名称和维度
            String[] parts = parseMetricName(metricName);
            String chartName = parts[0];
            String dimension = parts[1];
            
            log.info("[DetectAnomalyTool] 获取时间序列数据: chart={}, dimension={}", chartName, dimension);
            
            // 查询最近1小时的数据，60个点
            return netDataClient.queryTimeSeries(chartName, "-3600", "0", 60, "average", dimension);
        } catch (Exception e) {
            log.warn("[DetectAnomalyTool] 获取时间序列数据失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析指标名称，提取 chart 名称和维度
     * 支持格式: "system.ram", "system.ram.used", "memory", "memory.used"
     * 
     * @param metricName 指标名称
     * @return String[] {chartName, dimension}
     */
    private String[] parseMetricName(String metricName) {
        String lowerMetric = metricName.toLowerCase();
        
        // 处理常见模式
        if (lowerMetric.startsWith("system.ram")) {
            if (lowerMetric.equals("system.ram") || lowerMetric.equals("system.ram.all")) {
                return new String[]{"system.ram", null};
            }
            // system.ram.used -> chart: system.ram, dimension: used
            String[] parts = lowerMetric.split("\\.");
            if (parts.length >= 3) {
                return new String[]{"system.ram", parts[2]};
            }
            return new String[]{"system.ram", null};
        }
        
        if (lowerMetric.startsWith("system.cpu")) {
            if (lowerMetric.equals("system.cpu") || lowerMetric.equals("system.cpu.all")) {
                return new String[]{"system.cpu", null};
            }
            String[] parts = lowerMetric.split("\\.");
            if (parts.length >= 3) {
                return new String[]{"system.cpu", parts[2]};
            }
            return new String[]{"system.cpu", null};
        }
        
        // 通用模式: context.dimension
        if (lowerMetric.contains(".")) {
            String[] parts = lowerMetric.split("\\.");
            if (parts.length >= 2) {
                String chart = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
                String dimension = parts[parts.length - 1];
                return new String[]{chart, dimension};
            }
        }
        
        // 默认映射
        return mapMetricToChart(metricName);
    }

    /**
     * 将指标名称映射到 NetData chart 名称（不包含维度）
     */
    private String[] mapMetricToChart(String metricName) {
        String lowerMetric = metricName.toLowerCase();
        return switch (lowerMetric) {
            case "memory", "memory.used", "memory.free", "memory.buffered", "memory.cached" -> 
                new String[]{"system.ram", lowerMetric.contains(".") ? lowerMetric.substring(7) : null};
            case "cpu", "cpu.usage", "cpu.user", "cpu.system", "cpu.idle" -> 
                new String[]{"system.cpu", lowerMetric.contains(".") ? lowerMetric.substring(4) : null};
            case "disk", "disk.usage", "disk.used", "disk.free" -> 
                new String[]{"disk_space._", lowerMetric.contains(".") ? lowerMetric.substring(5) : null};
            case "network", "net", "net.receive", "net.send" -> 
                new String[]{"net", lowerMetric.contains(".") ? lowerMetric.substring(4) : null};
            default -> new String[]{metricName, null};
        };
    }

    /**
     * 执行真实的异常检测（基于 Z-score 统计方法）
     */
    private String performAnomalyDetection(String metricName, double threshold, Map<String, Object> timeSeriesData) {
        List<List<Object>> data = (List<List<Object>>) timeSeriesData.get("data");
        List<String> labels = (List<String>) timeSeriesData.get("labels");
        
        if (data == null || data.isEmpty()) {
            return String.format("错误: 指标 '%s' 没有数据", metricName);
        }

        // 提取数值列（跳过时间列）
        List<Double> values = new ArrayList<>();
        int valueIndex = labels != null && labels.size() > 1 ? 1 : 1;
        
        for (List<Object> point : data) {
            if (point.size() > valueIndex && point.get(valueIndex) instanceof Number) {
                values.add(((Number) point.get(valueIndex)).doubleValue());
            }
        }

        if (values.isEmpty()) {
            return String.format("错误: 无法从指标 '%s' 提取数值数据", metricName);
        }

        // 计算统计值
        double mean = calculateMean(values);
        double stdDev = calculateStdDev(values, mean);
        double zThreshold = mapThresholdToZScore(threshold);
        
        log.info("[DetectAnomalyTool] 统计计算完成: mean={}, stdDev={}, zThreshold={}", mean, stdDev, zThreshold);

        // 检测异常点
        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            List<Object> point = data.get(i);
            if (point.size() > valueIndex && point.get(valueIndex) instanceof Number) {
                double value = ((Number) point.get(valueIndex)).doubleValue();
                double zScore = Math.abs((value - mean) / stdDev);
                
                boolean isAnomaly = zScore > zThreshold;
                if (isAnomaly) {
                    Map<String, Object> anomaly = new HashMap<>();
                    anomaly.put("timestamp", point.get(0));
                    anomaly.put("value", value);
                    anomaly.put("zScore", Math.round(zScore * 100.0) / 100.0);
                    anomaly.put("isAnomaly", true);
                    anomalies.add(anomaly);
                }
            }
        }

        // 生成检测报告
        return formatDetectionResult(metricName, threshold, mean, stdDev, anomalies, data.size());
    }

    /**
     * 计算平均值
     */
    private double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 计算标准差
     */
    private double calculateStdDev(List<Double> values, double mean) {
        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();
        return Math.sqrt(sumSquaredDiff / values.size());
    }

    /**
     * 将阈值映射到 Z-score
     */
    private double mapThresholdToZScore(double threshold) {
        // threshold: 0-1 -> Z-score: 1.65-3.09 (对应 90%-99.9% 置信区间)
        return 1.65 + (threshold * 1.44);
    }

    /**
     * 格式化检测结果
     */
    private String formatDetectionResult(String metricName, double threshold, double mean, 
                                         double stdDev, List<Map<String, Object>> anomalies, int totalPoints) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== 异常检测结果 ===\n");
        sb.append("指标名称: ").append(metricName).append("\n");
        sb.append("检测时间: ").append(now).append("\n");
        sb.append("数据来源: NetData 真实监控数据\n");
        sb.append("检测方法: Z-score 统计分析\n");
        sb.append("────────────────────────\n");
        sb.append(String.format("统计摘要:\n"));
        sb.append(String.format("  数据点总数: %d\n", totalPoints));
        sb.append(String.format("  平均值: %.2f\n", mean));
        sb.append(String.format("  标准差: %.2f\n", stdDev));
        sb.append(String.format("  异常阈值: %.2f (对应 Z-score: %.2f)\n", threshold, mapThresholdToZScore(threshold)));
        sb.append("────────────────────────\n");
        
        if (anomalies.isEmpty()) {
            sb.append("检测结果: 未发现异常\n");
            sb.append("结论: 指标 ").append(metricName).append(" 运行正常，无异常波动。\n");
        } else {
            sb.append(String.format("检测结果: 发现 %d 个异常点\n", anomalies.size()));
            sb.append("异常详情:\n");
            
            int count = 1;
            for (Map<String, Object> anomaly : anomalies) {
                sb.append(String.format("  [%d] 时间戳: %s\n", count++, anomaly.get("timestamp")));
                sb.append(String.format("       值: %.2f\n", anomaly.get("value")));
                sb.append(String.format("       Z-score: %.2f (超过阈值)\n", anomaly.get("zScore")));
            }
            
            sb.append("────────────────────────\n");
            sb.append("结论: 指标 ").append(metricName).append(" 存在异常波动，");
            sb.append("建议进一步排查相关服务和进程。\n");
        }
        
        return sb.toString();
    }

    /**
     * 解析阈值参数
     */
    private double parseThreshold(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.8;
        }
    }
}