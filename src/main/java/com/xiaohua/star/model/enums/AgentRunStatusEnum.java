package com.xiaohua.star.model.enums;

/**
 * 一次运行的流转状态（{@code agent_run.status}）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p>M9 用到 {@link #SUSPENDED}；M10 用到 {@link #WAITING_APPROVAL}（副作用工具待人工确认）。
 * {@link #CANCELLED} 目前只保留给「超时封存」等后续功能。
 */
public enum AgentRunStatusEnum {

    PENDING,
    RUNNING,
    SUSPENDED,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 终态：进入后不再改变。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
