package com.netdata.ops.core.agent.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 事件总线
 *
 * 基于 Spring ApplicationEvent 实现的 Agent 间通信总线。
 * 
 * 核心功能：
 * 1. 发布消息：将 AgentMessage 封装为 AgentEvent 发布到 Spring 容器
 * 2. 消息路由：根据 targetAgent 精确投递，为空时广播给所有注册的 handler
 * 3. 异步处理：通过 @Async 实现非阻塞消息处理
 * 4. 消息统计：记录发布/消费计数，供监控使用
 * 5. 消息历史：保留最近 100 条消息用于审计和调试
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class AgentEventBus {

    private final ApplicationEventPublisher eventPublisher;
    
    /** 注册的消息处理器 */
    private final Map<String, AgentMessageHandler> handlers = new ConcurrentHashMap<>();
    
    /** 消息发布计数 */
    private final AtomicLong publishedCount = new AtomicLong(0);
    
    /** 消息消费计数 */
    private final AtomicLong consumedCount = new AtomicLong(0);
    
    /** 消息历史（最近 100 条） */
    private final List<AgentMessage> messageHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY_SIZE = 100;
    
    public AgentEventBus(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }
    
    // ==================== Handler 注册 ====================
    
    /** 注册消息处理器 */
    public void registerHandler(AgentMessageHandler handler) {
        handlers.put(handler.getAgentName(), handler);
        log.info("[事件总线] 注册处理器: {}", handler.getAgentName());
    }
    
    /** 注销消息处理器 */
    public void unregisterHandler(String agentName) {
        handlers.remove(agentName);
        log.info("[事件总线] 注销处理器: {}", agentName);
    }
    
    // ==================== 消息发布 ====================
    
    /** 
     * 发布消息
     * 自动设置 messageId 和 timestamp
     */
    public void publish(AgentMessage message) {
        // 补全消息元数据
        if (message.getMessageId() == null) {
            message.setMessageId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(Instant.now());
        }
        
        log.info("[事件总线] 发布消息: id={}, type={}, from={}, to={}",
                message.getMessageId(), message.getType(),
                message.getSourceAgent(), message.getTargetAgent());
        
        // 记录历史
        addToHistory(message);
        publishedCount.incrementAndGet();
        
        // 发布 Spring 事件
        eventPublisher.publishEvent(new AgentEvent(this, message));
    }
    
    // ==================== 事件监听 ====================
    
    /** 异步监听并路由 AgentEvent */
    @Async("agentEventExecutor")
    @EventListener
    public void onAgentEvent(AgentEvent event) {
        AgentMessage message = event.getMessage();
        
        String target = message.getTargetAgent();
        
        if (target != null && !target.isEmpty()) {
            // 点对点：投递给指定 Agent
            AgentMessageHandler handler = handlers.get(target);
            if (handler != null && handler.supports(message.getType())) {
                try {
                    handler.handle(message);
                    consumedCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("[事件总线] 处理消息异常: handler={}, messageId={}",
                            target, message.getMessageId(), e);
                }
            } else {
                log.warn("[事件总线] 目标处理器不存在或不支持此消息类型: target={}, type={}",
                        target, message.getType());
            }
        } else {
            // 广播：投递给所有支持此消息类型的 handler
            for (AgentMessageHandler handler : handlers.values()) {
                if (handler.supports(message.getType())) {
                    try {
                        handler.handle(message);
                        consumedCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("[事件总线] 广播处理异常: handler={}, messageId={}",
                                handler.getAgentName(), message.getMessageId(), e);
                    }
                }
            }
        }
    }
    
    // ==================== 消息历史 ====================
    
    private void addToHistory(AgentMessage message) {
        messageHistory.add(message);
        // 保持最近 100 条
        while (messageHistory.size() > MAX_HISTORY_SIZE) {
            messageHistory.remove(0);
        }
    }
    
    public List<AgentMessage> getMessageHistory() {
        return Collections.unmodifiableList(messageHistory);
    }
    
    // ==================== 监控指标 ====================
    
    public long getPublishedCount() { return publishedCount.get(); }
    public long getConsumedCount() { return consumedCount.get(); }
    public int getRegisteredHandlerCount() { return handlers.size(); }
}
