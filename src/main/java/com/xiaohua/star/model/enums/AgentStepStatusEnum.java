package com.xiaohua.star.model.enums;

/**
 * 执行步骤状态（{@code agent_step.status}）。见 knowledge/12。
 *
 * <p>M8：LLM/工具步骤走 {@link #PENDING} → {@link #SUCCEEDED} / {@link #FAILED}；
 * {@link #INTERRUPTED}（用户中断的 LLM 调用）与「结果未知的副作用步骤转 FAILED」的
 * 更细语义属 M9。启动扫描会把「执行结果未知」的副作用步骤置为 {@link #FAILED}。
 */
public enum AgentStepStatusEnum {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED
}
