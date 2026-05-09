package com.netdata.ops.core.agent.tools;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 *
 * <p>职责：
 * <ul>
 *   <li>在 Spring 容器启动时自动扫描所有带 {@link AgentTool} 注解的 Bean</li>
 *   <li>将工具注册到内部注册表，供 ReActEngine 查询和调用</li>
 *   <li>提供工具描述列表，供 LLM 构建 Prompt 时使用</li>
 * </ul>
 *
 * <p>设计理由：
 * <ol>
 *   <li>解耦工具定义和引擎逻辑，新增工具只需实现接口+加注解</li>
 *   <li>使用 ConcurrentHashMap 保证并发安全</li>
 *   <li>通过 ApplicationContextAware 在容器就绪后自动发现工具</li>
 * </ol>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class ToolRegistry implements ApplicationContextAware {

    /**
     * 工具注册表：工具名称 -> 工具实例
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Spring 容器就绪后，自动扫描并注册所有带 @AgentTool 注解的 Bean
     */
    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(AgentTool.class);

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (bean instanceof Tool tool) {
                AgentTool annotation = bean.getClass().getAnnotation(AgentTool.class);
                if (annotation != null) {
                    tools.put(annotation.name(), tool);
                    log.info("[工具注册] 注册工具: {} - {}", annotation.name(), annotation.description());
                }
            } else {
                log.warn("[工具注册] Bean {} 标注了 @AgentTool 但未实现 Tool 接口，跳过",
                        entry.getKey());
            }
        }

        log.info("[工具注册] 完成，共注册 {} 个工具: {}", tools.size(), tools.keySet());
    }

    /**
     * 根据名称获取工具
     *
     * @param name 工具名称
     * @return 工具实例，不存在返回 null
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 判断工具是否存在
     *
     * @param name 工具名称
     * @return 是否已注册
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取所有已注册工具的描述信息（供 LLM Prompt 使用）
     *
     * @return 工具描述列表
     */
    public List<ToolDescription> getToolDescriptions() {
        return tools.values().stream()
                .map(tool -> {
                    AgentTool annotation = tool.getClass().getAnnotation(AgentTool.class);
                    return ToolDescription.builder()
                            .name(annotation.name())
                            .description(annotation.description())
                            .parameters(Arrays.asList(annotation.parameters()))
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取已注册工具数量
     */
    public int size() {
        return tools.size();
    }

    // ========== 内部类 ==========

    /**
     * 工具描述（供 LLM 使用的结构化信息）
     */
    @Data
    @Builder
    public static class ToolDescription {
        /** 工具名称 */
        private String name;
        /** 工具功能描述 */
        private String description;
        /** 参数描述列表 */
        private List<String> parameters;
    }
}
