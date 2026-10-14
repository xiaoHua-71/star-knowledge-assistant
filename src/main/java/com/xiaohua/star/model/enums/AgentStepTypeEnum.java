package com.xiaohua.star.model.enums;

/**
 * 执行步骤类型（{@code agent_step.type}）。见 knowledge/10 3.3 节。
 *
 * <p>⚠️ {@link #TOOL_RESULT} <b>不落库</b>——工具结果直接回填到对应 {@link #TOOL_CALL} 行
 * （拆成两行的话，「意图已落库但结果未回填」要靠 join 判定，回填同一行只需判空）。
 * 保留该枚举值只为让「事件类型 / 日志」的命名对齐。
 */
public enum AgentStepTypeEnum {

    LLM_CALL,
    TOOL_CALL,
    TOOL_RESULT
}
