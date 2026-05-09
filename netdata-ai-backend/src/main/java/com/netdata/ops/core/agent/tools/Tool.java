package com.netdata.ops.core.agent.tools;

import java.util.Map;

/**
 * Agent 工具接口
 *
 * <p>所有 ReAct 引擎可调用的工具都需要实现此接口。
 * 工具通过 {@link AgentTool} 注解声明元信息，并通过 {@link ToolRegistry} 自动注册。
 *
 * <p>设计原则：
 * <ul>
 *   <li>每个工具职责单一，只做一件事</li>
 *   <li>execute 方法返回文本结果，供 LLM 作为 Observation 使用</li>
 *   <li>工具内部处理异常，不向外抛出，失败时返回错误描述文本</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
public interface Tool {

    /**
     * 获取工具名称
     *
     * @return 工具唯一名称标识
     */
    String getName();

    /**
     * 获取工具描述
     *
     * @return 工具功能描述（供 LLM 理解）
     */
    String getDescription();

    /**
     * 执行工具
     *
     * @param params 工具参数（由 LLM 决策生成）
     * @return 执行结果的文本描述（作为 ReAct 的 Observation）
     */
    String execute(Map<String, Object> params);
}
