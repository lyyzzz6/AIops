package com.netdata.ops.core.agent.intent;

import com.netdata.ops.core.agent.BaseAgent;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 意图分类结果
 *
 * <p>封装意图分类器的输出，包含：
 * <ul>
 *   <li>识别的意图类型及置信度</li>
 *   <li>LLM 的推理过程（用于可解释性和调试）</li>
 *   <li>建议使用的工具列表</li>
 *   <li>分类来源标识（用于监控不同分类器的命中率）</li>
 * </ul>
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Data
@Builder
public class IntentResult {

    /**
     * 识别的意图类型
     */
    private BaseAgent.IntentType intentType;

    /**
     * 分类置信度（0.0 ~ 1.0）
     */
    private double confidence;

    /**
     * LLM 的推理过程（规则分类时为匹配描述）
     */
    private String reasoning;

    /**
     * 建议使用的工具列表
     */
    private List<String> suggestedTools;

    /**
     * 是否来自缓存
     */
    private boolean fromCache;

    /**
     * 分类来源标识：
     * - "rule"：规则快速路径
     * - "llm"：LLM 语义分类
     * - "cache"：缓存命中
     */
    private String classifierSource;
}
