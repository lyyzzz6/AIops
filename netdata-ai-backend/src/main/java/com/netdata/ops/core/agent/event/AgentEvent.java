package com.netdata.ops.core.agent.event;

import org.springframework.context.ApplicationEvent;

/**
 * Agent 事件（Spring ApplicationEvent 封装）
 *
 * 将 AgentMessage 封装为 Spring 事件，利用 Spring 事件机制
 * 实现 Agent 间松耦合的发布-订阅通信。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
public class AgentEvent extends ApplicationEvent {
    private final AgentMessage message;
    
    public AgentEvent(Object source, AgentMessage message) {
        super(source);
        this.message = message;
    }
    
    public AgentMessage getMessage() {
        return message;
    }
}
