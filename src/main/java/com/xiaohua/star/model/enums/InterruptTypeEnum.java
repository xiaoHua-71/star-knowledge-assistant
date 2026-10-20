package com.xiaohua.star.model.enums;

/**
 * 运行挂起的原因（{@code agent_run.interruptType}）。见 frontend/02 的 {@code interruptType} 一览。
 *
 * <p>M8 写入 {@link #SERVER_RESTART}；M9 写入 {@link #USER_STOP} / {@link #NEW_MESSAGE}；
 * M10 写入 {@link #APPROVAL_REQUIRED}。
 */
public enum InterruptTypeEnum {

    /** 用户点了停止 */
    USER_STOP,
    /** 用户在运行中又发了新消息 */
    NEW_MESSAGE,
    /** 服务重启（启动扫描写入） */
    SERVER_RESTART,
    /** 副作用工具待人工确认 */
    APPROVAL_REQUIRED
}
