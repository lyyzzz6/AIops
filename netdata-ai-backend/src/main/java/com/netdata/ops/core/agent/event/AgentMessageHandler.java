package com.netdata.ops.core.agent.event;

/**
 * Agent 消息处理器接口
 *
 * Agent 实现此接口以接收和处理来自事件总线的异步消息。
 * AgentEventBus 会根据 targetAgent 和 supports() 进行消息路由。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
public interface AgentMessageHandler {
    /** 获取处理器对应的 Agent 名称 */
    String getAgentName();
    /** 判断是否支持处理此消息类型 */
    boolean supports(AgentMessage.MessageType type);
    /** 处理消息 */
    void handle(AgentMessage message);
}
