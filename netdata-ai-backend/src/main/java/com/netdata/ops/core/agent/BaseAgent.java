package com.netdata.ops.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ============================================================
 * Agent 基类 - 工业级模板方法实现
 * ============================================================
 *
 * 设计模式：模板方法 + 策略模式 + 拦截器链
 *
 * 核心能力：
 * - 超时控制：CompletableFuture + 可配置 timeout，防止 LLM 调用卡死
 * - TraceId 链路追踪：每次执行生成唯一 traceId，写入 SLF4J MDC
 * - 生命周期钩子：onStart/onComplete/onError/onTimeout 可选覆盖
 * - 拦截器链：支持前置/后置/异常处理，实现关注点分离
 * - 指标采集：通过 AgentMetrics 上报执行指标
 * - 重试能力：可配置最大重试次数和重试间隔
 *
 * 向后兼容：
 * 子类仍然只需实现 doExecute()，无需感知超时、重试、拦截器等基础设施。
 * 原有 super(name, type) 构造方式不变。
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@Slf4j
public abstract class BaseAgent {

    /**
     * Agent 名称
     */
    protected final String name;

    /**
     * Agent 类型
     */
    protected final AgentType type;

    /**
     * 指标采集器（可选注入，为 null 时跳过指标上报）
     * 为什么可选：不是所有环境都需要指标（如单元测试），避免强依赖
     */
    private final AgentMetrics metrics;

    /**
     * 拦截器链（按注册顺序执行）
     * 为什么用 List：保证执行顺序可预测，便于调试
     */
    private final List<AgentInterceptor> interceptors;

    // ==================== 构造函数 ====================

    /**
     * 基础构造函数 - 保持向后兼容
     * 子类现有代码 super(name, type) 无需任何修改
     */
    protected BaseAgent(String name, AgentType type) {
        this.name = name;
        this.type = type;
        this.metrics = null;
        this.interceptors = Collections.emptyList();
    }

    /**
     * 增强构造函数 - 支持注入指标和拦截器
     * 为什么提供第二个构造：渐进式升级，新代码可选择增强能力，旧代码不受影响
     */
    protected BaseAgent(String name, AgentType type, AgentMetrics metrics, List<AgentInterceptor> interceptors) {
        this.name = name;
        this.type = type;
        this.metrics = metrics;
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
    }

    // ==================== 模板方法：核心执行流程 ====================

    /**
     * 执行 Agent 任务（模板方法）
     *
     * 完整执行流程：
     * 1. 生成/传递 traceId → 写入 MDC（实现日志自动关联）
     * 2. 设置 deadline（如果 context 没有指定）
     * 3. 增加活跃执行计数
     * 4. 执行前置拦截器
     * 5. 验证上下文合法性
     * 6. 带超时控制执行 doExecute()（含重试）
     * 7. 成功路径：后置拦截器 + 指标上报
     * 8. 超时路径：onTimeout 钩子 + 超时指标
     * 9. 异常路径：onError 钩子 + 异常拦截器 + 失败指标
     * 10. finally：减少活跃计数 + 清理 MDC
     *
     * @param context 执行上下文
     * @return 执行结果
     */
    public final AgentResult execute(AgentContext context) {
        if (context == null) {
            throw new IllegalArgumentException("AgentContext 不能为空");
        }

        // === Step 1: 链路追踪初始化 ===
        String traceId = initTraceId(context);
        MDC.put("traceId", traceId);
        MDC.put("agentName", name);

        long startTime = System.currentTimeMillis();
        context.setStartTime(Instant.now());

        log.info("Agent [{}] 开始执行任务，traceId={}", name, traceId);

        try {
            // === Step 2: 设置截止时间 ===
            if (context.getDeadline() == null) {
                context.setDeadline(Instant.now().plusMillis(getTimeoutMs()));
            }

            // === Step 3: 增加活跃计数 ===
            if (metrics != null) {
                metrics.incrementActiveCount(name);
            }

            // === Step 4: 前置拦截器 ===
            for (AgentInterceptor interceptor : interceptors) {
                interceptor.preExecute(context);
            }

            // === Step 5: 上下文验证 ===
            validateContext(context);

            // === 生命周期钩子: onStart ===
            onStart(context);

            // === Step 6: 带超时和重试的核心执行 ===
            AgentResult result = executeWithRetry(context);

            // === Step 7: 成功路径 ===
            result.setAgentName(name);
            result.setAgentType(type);
            result.setTraceId(traceId);
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            result.setSuccess(true);

            // 后置拦截器
            for (AgentInterceptor interceptor : interceptors) {
                interceptor.postExecute(context, result);
            }

            // 生命周期钩子: onComplete
            onComplete(context, result);

            // 指标上报
            if (metrics != null) {
                metrics.recordExecution(name, result.getExecutionTimeMs(), true);
            }

            log.info("Agent [{}] 执行完成，耗时 {}ms，traceId={}", name, result.getExecutionTimeMs(), traceId);
            return result;

        } catch (AgentTimeoutException e) {
            // === Step 8: 超时路径 ===
            long duration = System.currentTimeMillis() - startTime;
            log.warn("Agent [{}] 执行超时，已耗时 {}ms，traceId={}", name, duration, traceId);

            onTimeout(context);

            if (metrics != null) {
                metrics.recordTimeout(name);
                metrics.recordExecution(name, duration, false);
            }

            return AgentResult.builder()
                    .agentName(name)
                    .agentType(type)
                    .traceId(traceId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .executionTimeMs(duration)
                    .build();

        } catch (Exception e) {
            // === Step 9: 异常路径 ===
            long duration = System.currentTimeMillis() - startTime;
            log.error("Agent [{}] 执行失败，traceId={}", name, traceId, e);

            // 生命周期钩子: onError
            onError(context, e);

            // 异常拦截器
            for (AgentInterceptor interceptor : interceptors) {
                interceptor.onError(context, e);
            }

            // 指标上报
            if (metrics != null) {
                metrics.recordExecution(name, duration, false);
            }

            return AgentResult.builder()
                    .agentName(name)
                    .agentType(type)
                    .traceId(traceId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .executionTimeMs(duration)
                    .build();

        } finally {
            // === Step 10: 清理 ===
            if (metrics != null) {
                metrics.decrementActiveCount(name);
            }
            MDC.remove("traceId");
            MDC.remove("agentName");
        }
    }

    // ==================== 重试机制 ====================

    /**
     * 带重试的执行逻辑
     *
     * 为什么在 BaseAgent 内实现重试而非外部：
     * 1. 重试是 Agent 执行的核心关注点，不应泄漏到调用方
     * 2. 重试需要感知 context 的 retryCount，便于子类差异化处理
     * 3. 统一的重试指标采集
     */
    private AgentResult executeWithRetry(AgentContext context) {
        int maxRetries = getMaxRetries();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            context.setRetryCount(attempt);

            try {
                AgentResult result = executeWithTimeout(context);
                result.setRetryCount(attempt);
                return result;
            } catch (AgentTimeoutException e) {
                // 超时不重试，直接抛出
                throw e;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long delay = getRetryDelay().toMillis();
                    log.warn("Agent [{}] 第 {} 次执行失败，{}ms 后重试。原因: {}",
                            name, attempt + 1, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试等待被中断", ie);
                    }
                }
            }
        }

        // 所有重试均失败
        throw new RuntimeException(
                String.format("Agent [%s] 在 %d 次重试后仍然失败", name, maxRetries), lastException);
    }

    /**
     * 带超时控制的单次执行
     *
     * 为什么用 CompletableFuture 而非简单的 Future：
     * 1. 支持超时取消
     * 2. 异常传播更清晰
     * 3. 可组合性更好（未来支持并行子 Agent 调用）
     */
    private AgentResult executeWithTimeout(AgentContext context) {
        long remainingMs = calculateRemainingMs(context);

        CompletableFuture<AgentResult> future = CompletableFuture
                .supplyAsync(() -> doExecute(context));

        try {
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentTimeoutException(name, remainingMs, e);
        } catch (ExecutionException e) {
            // 解包 CompletableFuture 的 ExecutionException
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Agent 执行被中断", e);
        }
    }

    /**
     * 计算剩余可用时间
     * 为什么基于 deadline 计算：支持嵌套调用时的时间预算传递
     */
    private long calculateRemainingMs(AgentContext context) {
        if (context.getDeadline() != null) {
            long remaining = Duration.between(Instant.now(), context.getDeadline()).toMillis();
            if (remaining <= 0) {
                throw new AgentTimeoutException(name, getTimeoutMs());
            }
            return remaining;
        }
        return getTimeoutMs();
    }

    // ==================== 链路追踪 ====================

    /**
     * 初始化 traceId
     * 如果 context 已有 traceId（如从父 Agent 传递），则复用；否则生成新的
     */
    private String initTraceId(AgentContext context) {
        if (context.getTraceId() != null && !context.getTraceId().isEmpty()) {
            return context.getTraceId();
        }
        String traceId = UUID.randomUUID().toString().replace("-", "");
        context.setTraceId(traceId);
        return traceId;
    }

    // ==================== 生命周期钩子（子类可选覆盖） ====================

    /**
     * 执行开始钩子
     * 适用场景：初始化资源、记录审计日志
     */
    protected void onStart(AgentContext context) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 执行完成钩子
     * 适用场景：资源释放、结果缓存
     */
    protected void onComplete(AgentContext context, AgentResult result) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 执行异常钩子
     * 适用场景：告警通知、降级处理
     */
    protected void onError(AgentContext context, Exception e) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 执行超时钩子
     * 适用场景：超时告警、超时原因分析
     */
    protected void onTimeout(AgentContext context) {
        // 默认空实现，子类按需覆盖
    }

    // ==================== 可配置参数（子类可覆盖） ====================

    /**
     * 获取执行超时时间（毫秒）
     * 默认 30 秒，子类可根据任务特性调整
     * 例如：AnalysisAgent 的 ReAct 循环可能需要更长时间
     */
    protected long getTimeoutMs() {
        return 30_000L;
    }

    /**
     * 获取最大重试次数
     * 默认不重试（0），子类可按需开启
     * 例如：网络调用类 Agent 可设置 1-2 次重试
     */
    protected int getMaxRetries() {
        return 0;
    }

    /**
     * 获取重试间隔
     * 默认 1 秒，子类可调整
     */
    protected Duration getRetryDelay() {
        return Duration.ofSeconds(1);
    }

    // ==================== 上下文验证 ====================

    /**
     * 验证上下文
     *
     * @param context 执行上下文
     * @throws IllegalArgumentException 参数无效
     */
    protected void validateContext(AgentContext context) {
        if (context == null) {
            throw new IllegalArgumentException("上下文不能为空");
        }
        if (context.getQuery() == null || context.getQuery().isEmpty()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
    }

    // ==================== 抽象方法 ====================

    /**
     * 执行核心逻辑（子类实现）
     *
     * 子类只需关注业务逻辑，超时/重试/拦截器/指标等横切关注点由基类处理。
     *
     * @param context 执行上下文
     * @return 执行结果
     */
    protected abstract AgentResult doExecute(AgentContext context);

    // ==================== Getter ====================

    /**
     * 获取 Agent 名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取 Agent 类型
     */
    public AgentType getType() {
        return type;
    }

    // ==================== 内部类型定义 ====================

    /**
     * Agent 类型枚举
     */
    public enum AgentType {
        /**
         * 编排器 Agent
         */
        ORCHESTRATOR,
        /**
         * 查询 Agent（RAG 问答）
         */
        QUERY,
        /**
         * 分析 Agent（ReAct 诊断）
         */
        ANALYSIS,
        /**
         * 执行 Agent（Human-in-Loop）
         */
        EXECUTION
    }

    /**
     * 意图类型枚举
     */
    public enum IntentType {
        /**
         * 知识问答
         */
        KNOWLEDGE_QUERY,
        /**
         * 故障诊断
         */
        FAULT_DIAGNOSIS,
        /**
         * 命令执行
         */
        COMMAND_EXECUTE,
        /**
         * 混合意图
         */
        HYBRID
    }
}
