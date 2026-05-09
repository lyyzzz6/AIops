package com.netdata.ops.core.agent.event;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Agent 事件总线异步配置
 *
 * 提供独立的线程池用于事件处理，避免阻塞主线程。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Configuration
@EnableAsync
public class AgentEventConfig {

    @Bean("agentEventExecutor")
    public Executor agentEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-event-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
