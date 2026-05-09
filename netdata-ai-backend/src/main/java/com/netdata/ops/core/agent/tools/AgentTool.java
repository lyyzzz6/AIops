package com.netdata.ops.core.agent.tools;

import java.lang.annotation.*;

/**
 * Agent 工具注解
 *
 * <p>标注在工具实现类上，用于自动注册到 ToolRegistry。
 * ToolRegistry 在 Spring 容器启动时扫描所有带此注解的 Bean，
 * 自动提取名称、描述和参数信息，供 LLM 动态选择工具。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentTool {

    /**
     * 工具名称（唯一标识，供 LLM 调用时使用）
     */
    String name();

    /**
     * 工具描述（给 LLM 看，帮助 LLM 理解工具用途）
     */
    String description();

    /**
     * 参数描述列表（格式："参数名: 参数说明"）
     */
    String[] parameters() default {};
}
