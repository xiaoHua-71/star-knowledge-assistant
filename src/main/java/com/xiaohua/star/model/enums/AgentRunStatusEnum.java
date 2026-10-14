package com.xiaohua.star.model.enums;

/**
 * 一次运行的流转状态（{@code agent_run.status}）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p>M8 只用到 {@link #PENDING} → {@link #RUNNING} → {@link #COMPLETED} / {@link #FAILED}，
 * 以及启动扫描写入的 {@link #SUSPENDED}；{@link #WAITING_APPROVAL} / {@link #CANCELLED} 属 M9/M10。
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
