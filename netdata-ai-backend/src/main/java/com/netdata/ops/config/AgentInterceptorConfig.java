package com.netdata.ops.config;

import com.netdata.ops.core.agent.AgentAuditInterceptor;
import com.netdata.ops.core.agent.AgentInterceptor;
import com.netdata.ops.core.agent.LoggingAgentInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * ============================================================
 * Agent 拦截器配置
 * ============================================================
 *
 * 设计目的：
 * 集中注册所有 AgentInterceptor 实现，通过 Spring IoC 管理拦截器的生命周期和执行顺序。
 * BaseAgent 通过注入 List<AgentInterceptor> 自动获取所有已注册的拦截器。
 *
 * 执行顺序说明：
 * 1. LoggingAgentInterceptor - 先记录日志（即使审计失败也有日志可查）
 * 2. AgentAuditInterceptor  - 后记录审计（依赖日志作为兜底）
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Configuration
public class AgentInterceptorConfig {

    /**
     * 注册 Agent 拦截器列表
     *
     * 为什么显式声明顺序而非依赖 @Order：
     * 拦截器间存在逻辑依赖关系（日志先于审计），显式 List 比注解更直观可控
     *
     * @param auditInterceptor   审计拦截器
     * @param loggingInterceptor 日志拦截器
     * @return 有序的拦截器列表
     */
    @Bean
    public List<AgentInterceptor> agentInterceptors(
            AgentAuditInterceptor auditInterceptor,
            LoggingAgentInterceptor loggingInterceptor) {
        return List.of(loggingInterceptor, auditInterceptor);
    }
}
