package com.netdata.ops.core.agent;

/**
 * ============================================================
 * Agent 拦截器接口
 * ============================================================
 *
 * 设计目的：
 * 提供 AOP 风格的横切关注点处理能力，允许在 Agent 执行的不同阶段
 * 插入自定义逻辑（如日志、审计、限流、权限校验等），而无需修改 Agent 本身。
 *
 * 采用 default 方法使得实现类只需关注感兴趣的阶段，降低实现成本。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
public interface AgentInterceptor {

    /**
     * 前置处理 - 在 Agent 核心逻辑执行之前调用
     *
     * 适用场景：参数增强、权限校验、限流判断等
     *
     * @param context 执行上下文
     */
    default void preExecute(AgentContext context) {}

    /**
     * 后置处理 - 在 Agent 成功执行之后调用
     *
     * 适用场景：结果审计、指标上报、缓存写入等
     *
     * @param context 执行上下文
     * @param result  执行结果
     */
    default void postExecute(AgentContext context, AgentResult result) {}

    /**
     * 异常处理 - 在 Agent 执行发生异常时调用
     *
     * 适用场景：异常告警、降级处理、错误上报等
     *
     * @param context 执行上下文
     * @param e       异常对象
     */
    default void onError(AgentContext context, Exception e) {}
}
