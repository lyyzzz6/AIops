package com.netdata.ops.core.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================
 * Agent 指标收集器
 * ============================================================
 *
 * 设计目的：
 * 基于 Micrometer 统一采集 Agent 执行指标，为可观测性提供数据支撑。
 * 通过 MeterRegistry 对接 Prometheus/Grafana 监控体系，实现：
 * - 执行耗时分布（Timer）
 * - 成功/失败计数（Counter）
 * - 超时事件计数（Counter）
 * - 实时并发数（Gauge via AtomicInteger）
 *
 * 使用 ConcurrentHashMap 缓存每个 Agent 的活跃计数器，避免重复创建。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 缓存每个 Agent 的活跃执行数，用于注册 Gauge 指标
     * 为什么用 ConcurrentHashMap：多 Agent 并发执行时保证线程安全
     */
    private final ConcurrentHashMap<String, AtomicInteger> activeCountMap = new ConcurrentHashMap<>();

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次 Agent 执行
     *
     * @param agentName  Agent 名称（作为 tag 区分不同 Agent）
     * @param durationMs 执行耗时（毫秒）
     * @param success    是否成功
     */
    public void recordExecution(String agentName, long durationMs, boolean success) {
        // Timer 记录耗时分布，便于计算 P50/P99 等分位数
        Timer.builder("agent.execution.duration")
                .tag("agent", agentName)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        // Counter 记录执行总数，按成功/失败分类
        Counter.builder("agent.execution.count")
                .tag("agent", agentName)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录超时事件
     *
     * @param agentName Agent 名称
     */
    public void recordTimeout(String agentName) {
        Counter.builder("agent.execution.timeout")
                .tag("agent", agentName)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 增加活跃执行数
     * 为什么需要：监控系统实时展示每个 Agent 的并发执行数，用于容量预警
     *
     * @param agentName Agent 名称
     */
    public void incrementActiveCount(String agentName) {
        getOrCreateActiveCounter(agentName).incrementAndGet();
    }

    /**
     * 减少活跃执行数
     *
     * @param agentName Agent 名称
     */
    public void decrementActiveCount(String agentName) {
        getOrCreateActiveCounter(agentName).decrementAndGet();
    }

    /**
     * 获取或创建某个 Agent 的活跃计数器
     * 首次创建时同时注册 Gauge 指标到 MeterRegistry
     */
    private AtomicInteger getOrCreateActiveCounter(String agentName) {
        return activeCountMap.computeIfAbsent(agentName, name -> {
            AtomicInteger counter = new AtomicInteger(0);
            // Gauge 实时反映当前并发数，不需要手动 increment/decrement Gauge 本身
            meterRegistry.gauge("agent.active.count", 
                    io.micrometer.core.instrument.Tags.of("agent", name), counter);
            return counter;
        });
    }
}
