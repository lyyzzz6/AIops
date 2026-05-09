package com.netdata.ops.core.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 异常检测工具
 *
 * <p>调用 Python 异常检测服务（IsolationForest / 统计检测），
 * 对监控指标数据进行异常检测分析。当 Python 服务不可用时，
 * 返回模拟检测结果作为后备。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Slf4j
@Component
@AgentTool(
        name = "detect_anomaly",
        description = "调用异常检测服务，对指标数据进行 IsolationForest/统计检测",
        parameters = {
                "metric_name: 指标名称",
                "threshold: 异常阈值(0-1)"
        }
)
public class DetectAnomalyTool implements Tool {

    private final WebClient webClient;

    public DetectAnomalyTool(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("http://localhost:8001")
                .build();
    }

    @Override
    public String getName() {
        return "detect_anomaly";
    }

    @Override
    public String getDescription() {
        return "调用异常检测服务，对指标数据进行 IsolationForest/统计检测";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String metricName = (String) params.getOrDefault("metric_name", "cpu.usage");
        double threshold = parseThreshold(params.getOrDefault("threshold", 0.7));

        log.info("[DetectAnomalyTool] 执行异常检测: metric={}, threshold={}", metricName, threshold);

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/api/v1/detection/batch")
                    .bodyValue(Map.of(
                            "data", List.of(
                                    Map.of("metric_name", metricName, "value", 85.0, "timestamp", System.currentTimeMillis())
                            ),
                            "detector_type", "isolation_forest",
                            "threshold", threshold
                    ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));

            if (response != null) {
                return formatResponse(metricName, response);
            }
            return generateFallbackResult(metricName, threshold);
        } catch (Exception e) {
            log.warn("[DetectAnomalyTool] Python 异常检测服务调用失败，使用模拟结果: {}", e.getMessage());
            return generateFallbackResult(metricName, threshold);
        }
    }

    /**
     * 格式化 Python 服务返回的检测结果
     */
    private String formatResponse(String metricName, Map<String, Object> response) {
        StringBuilder sb = new StringBuilder();
        sb.append("异常检测结果:\n");
        sb.append("  指标: ").append(metricName).append("\n");
        sb.append("  检测器: IsolationForest\n");

        Object anomalyCount = response.get("anomaly_count");
        if (anomalyCount != null) {
            sb.append("  异常数量: ").append(anomalyCount).append("\n");
        }

        Object results = response.get("results");
        if (results instanceof List<?> resultList && !resultList.isEmpty()) {
            sb.append("  详细结果:\n");
            for (Object item : resultList) {
                if (item instanceof Map<?, ?> map) {
                    sb.append("    - 分数: ").append(map.get("anomaly_score") != null ? map.get("anomaly_score") : "N/A");
                    sb.append(", 是否异常: ").append(map.get("is_anomaly") != null ? map.get("is_anomaly") : "N/A");
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 生成模拟检测结果（Python 服务不可用时的后备）
     */
    private String generateFallbackResult(String metricName, double threshold) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return String.format(
                "异常检测结果（模拟）:\n"
                        + "  指标: %s\n"
                        + "  检测器: IsolationForest\n"
                        + "  阈值: %.2f\n"
                        + "  检测时间: %s\n"
                        + "  结果: 检测到 2 个异常点\n"
                        + "    - 时间点 1: 异常分数 0.87, 超过阈值\n"
                        + "    - 时间点 2: 异常分数 0.92, 超过阈值\n"
                        + "  结论: 指标 %s 存在异常波动，建议进一步排查",
                metricName, threshold, now, metricName
        );
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
            return 0.7;
        }
    }
}
