package com.netdata.ops.core.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ============================================================
 * QueryAgent 提示词模板管理
 * ============================================================
 *
 * 设计理由：
 * 将 Prompt 模板从业务逻辑中解耦出来，统一管理。
 * 好处：
 * 1. 提示词可通过配置文件覆盖，无需改代码即可调优
 * 2. 不同场景（有检索结果/无检索结果）使用不同模板
 * 3. 便于后续做 Prompt 版本管理和 A/B 测试
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
public class QueryAgentPromptTemplate {

    /**
     * 系统提示词 —— 定义 LLM 的角色和行为约束
     *
     * 为什么这样设计：
     * - 明确限定"仅基于参考资料"避免 LLM 幻觉
     * - 要求标注引用来源，提升答案可追溯性
     * - 使用 Markdown 格式化，前端可直接渲染
     */
    @Value("${agent.query.system-prompt:}")
    private String customSystemPrompt;

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 NetData 智能运维系统的知识问答助手。
            请基于以下参考资料回答用户的问题。
            
            要求：
            1. 仅基于提供的参考资料回答，不要编造信息
            2. 如果参考资料不足以回答问题，请诚实说明
            3. 在回答中标注引用来源（如 [1]、[2]）
            4. 使用清晰的结构化格式（Markdown）
            5. 对专业术语给出简要解释
            6. 如果多条参考资料有冲突，请指出差异并给出综合判断
            """;

    /**
     * 用户提示词模板 —— 注入检索上下文和用户问题
     *
     * 为什么采用"参考资料 + 问题"的结构：
     * - 让 LLM 先看到资料再看问题，减少跳过资料直接回答的倾向
     * - 编号格式方便 LLM 生成 [1] [2] 等引用标注
     */
    private static final String USER_PROMPT_TEMPLATE = """
            ## 参考资料
            
            %s
            
            ## 用户问题
            
            %s
            
            请基于以上参考资料回答用户的问题。如果参考资料不足以完整回答，请标明哪些部分是基于资料的，哪些是你的推断。
            """;

    /**
     * 无检索结果时的兜底提示词
     *
     * 为什么不直接返回"找不到"：
     * - LLM 具备通用运维知识，即使没有 RAG 资料也可能给出有价值的建议
     * - 但需要明确标注"无参考资料佐证"，让用户自行判断可信度
     */
    @Value("${agent.query.no-result-prompt:}")
    private String customNoResultPrompt;

    private static final String DEFAULT_NO_RESULT_PROMPT = """
            你是 NetData 智能运维系统的知识问答助手。
            
            当前知识库中未检索到与用户问题直接相关的参考资料。
            请基于你的通用运维知识尝试回答以下问题，但必须在回答开头注明：
            
            > ⚠️ 以下回答未经知识库资料佐证，仅供参考。建议结合实际环境验证。
            
            ## 用户问题
            
            %s
            
            请尽你所能给出有帮助的回答，包括可能的排查思路、常见原因和建议操作。
            """;

    /**
     * 获取系统提示词
     *
     * @return 系统提示词（优先使用配置覆盖值）
     */
    public String getSystemPrompt() {
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            return customSystemPrompt;
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 构建用户提示词（有检索结果时）
     *
     * @param context 格式化后的检索上下文（带编号）
     * @param query   用户原始问题
     * @return 完整的用户提示词
     */
    public String buildUserPrompt(String context, String query) {
        return String.format(USER_PROMPT_TEMPLATE, context, query);
    }

    /**
     * 获取无检索结果时的完整提示词
     *
     * @param query 用户原始问题
     * @return 无结果场景的完整提示词
     */
    public String getNoResultPrompt(String query) {
        String template = (customNoResultPrompt != null && !customNoResultPrompt.isBlank())
                ? customNoResultPrompt
                : DEFAULT_NO_RESULT_PROMPT;
        return String.format(template, query);
    }

    /**
     * 组装完整 Prompt（系统提示 + 用户提示）
     *
     * 为什么将系统提示和用户提示合并为一个字符串：
     * - LLMFallbackHandler.call() 接受单个 prompt 参数
     * - 通过分隔符明确区分系统指令和用户输入，LLM 能更好理解各自角色
     *
     * @param context 格式化后的检索上下文
     * @param query   用户原始问题
     * @return 完整 Prompt
     */
    public String buildFullPrompt(String context, String query) {
        return getSystemPrompt() + "\n\n" + buildUserPrompt(context, query);
    }
}
