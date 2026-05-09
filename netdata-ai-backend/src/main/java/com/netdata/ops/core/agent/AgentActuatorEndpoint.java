package com.netdata.ops.core.agent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.search.Search;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================
 * Agent 自定义 Actuator 端点
 * ============================================================
 *
 * 设计目的：
 * 提供 /actuator/agents 端点，暴露所有 Agent 的运行状态概览和详细指标。
 * 运维人员可通过该端点快速了解各 Agent 的健康状况、执行频次和性能表现。
 *
 * 端点设计：
 * - GET /actuator/agents        → 所有 Agent 状态概览
 * - GET /actuator/agents/{name} → 单个 Agent 详细指标
 *
 * 数据来源：从 MeterRegistry 中聚合 AgentMetrics 注册的指标数据
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Endpoint(id = "agents")
public class AgentActuatorEndpoint {

    private final MeterRegistry meterRegistry;

    public AgentActuatorEndpoint(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 获取所有 Agent 状态概览
     *
     * 返回信息包括：Agent 名称、总执行次数、成功率、平均耗时
     * 为什么从 MeterRegistry 聚合而非自己维护计数：
     * 避免重复统计，确保与 Prometheus 导出的数据一致
     *
     * @return Agent 状态列表
     */
    @ReadOperation
    public Map<String, Object> agents() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", System.currentTimeMillis());

        // 从 Timer 指标中提取所有已注册的 Agent 名称
        Set<String> agentNames = extractAgentNames();

        List<Map<String, Object>> agentList = agentNames.stream()
                .map(this::buildAgentSummary)
                .collect(Collectors.toList());

        result.put("agents", agentList);
        result.put("totalAgents", agentList.size());
        return result;
    }

    /**
     * 获取单个 Agent 的详细指标
     *
     * @param name Agent 名称
     * @return 详细指标信息，包含执行次数、成功/失败分布、耗时统计、超时次数等
     */
    @ReadOperation
    public Map<String, Object> agent(@Selector String name) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("agentName", name);
        detail.put("timestamp", System.currentTimeMillis());

        // 执行计数统计
        long successCount = getCounterValue("agent.execution.count", name, "true");
        long failureCount = getCounterValue("agent.execution.count", name, "false");
        long totalCount = successCount + failureCount;

        detail.put("totalExecutions", totalCount);
        detail.put("successCount", successCount);
        detail.put("failureCount", failureCount);
        detail.put("successRate", totalCount > 0 ?
                String.format("%.2f%%", (double) successCount / totalCount * 100) : "N/A");

        // 耗时统计（从 Timer 获取）
        Map<String, Object> durationStats = getDurationStats(name);
        detail.put("duration", durationStats);

        // 超时次数
        long timeoutCount = getTimeoutCount(name);
        detail.put("timeoutCount", timeoutCount);

        // 当前活跃数
        detail.put("activeExecutions", getActiveCount(name));

        return detail;
    }

    /**
     * 从 MeterRegistry 中提取所有已注册的 Agent 名称
     * 通过查找 agent.execution.count Counter 的 "agent" tag 值来发现所有 Agent
     */
    private Set<String> extractAgentNames() {
        Set<String> names = new TreeSet<>();
        meterRegistry.find("agent.execution.count").counters().forEach(counter -> {
            String agentTag = counter.getId().getTag("agent");
            if (agentTag != null) {
                names.add(agentTag);
            }
        });
        return names;
    }

    /**
     * 构建单个 Agent 的概览信息
     */
    private Map<String, Object> buildAgentSummary(String agentName) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", agentName);

        long successCount = getCounterValue("agent.execution.count", agentName, "true");
        long failureCount = getCounterValue("agent.execution.count", agentName, "false");
        long totalCount = successCount + failureCount;

        summary.put("totalExecutions", totalCount);
        summary.put("successRate", totalCount > 0 ?
                String.format("%.2f%%", (double) successCount / totalCount * 100) : "N/A");

        // 平均耗时
        double avgDuration = getAverageDuration(agentName);
        summary.put("avgDurationMs", String.format("%.1f", avgDuration));

        return summary;
    }

    /**
     * 获取指定 Counter 的值
     */
    private long getCounterValue(String metricName, String agentName, String successValue) {
        Counter counter = meterRegistry.find(metricName)
                .tag("agent", agentName)
                .tag("success", successValue)
                .counter();
        return counter != null ? (long) counter.count() : 0;
    }

    /**
     * 获取 Agent 平均执行耗时
     */
    private double getAverageDuration(String agentName) {
        Collection<Timer> timers = meterRegistry.find("agent.execution.duration")
                .tag("agent", agentName)
                .timers();
        if (timers.isEmpty()) return 0.0;

        double totalTime = 0;
        long totalCount = 0;
        for (Timer timer : timers) {
            totalTime += timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
            totalCount += timer.count();
        }
        return totalCount > 0 ? totalTime / totalCount : 0.0;
    }

    /**
     * 获取耗时详细统计
     */
    private Map<String, Object> getDurationStats(String agentName) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Collection<Timer> timers = meterRegistry.find("agent.execution.duration")
                .tag("agent", agentName)
                .timers();

        if (timers.isEmpty()) {
            stats.put("avgMs", 0.0);
            stats.put("maxMs", 0.0);
            stats.put("totalMs", 0.0);
            return stats;
        }

        double totalTime = 0;
        double maxTime = 0;
        long totalCount = 0;
        for (Timer timer : timers) {
            totalTime += timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
            maxTime = Math.max(maxTime, timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
            totalCount += timer.count();
        }

        stats.put("avgMs", totalCount > 0 ? String.format("%.1f", totalTime / totalCount) : "0.0");
        stats.put("maxMs", String.format("%.1f", maxTime));
        stats.put("totalMs", String.format("%.1f", totalTime));
        stats.put("count", totalCount);
        return stats;
    }

    /**
     * 获取超时次数
     */
    private long getTimeoutCount(String agentName) {
        Counter counter = meterRegistry.find("agent.execution.timeout")
                .tag("agent", agentName)
                .counter();
        return counter != null ? (long) counter.count() : 0;
    }

    /**
     * 获取当前活跃执行数
     */
    private double getActiveCount(String agentName) {
        io.micrometer.core.instrument.Gauge gauge = meterRegistry.find("agent.active.count")
                .tag("agent", agentName)
                .gauge();
        return gauge != null ? gauge.value() : 0;
    }
}
