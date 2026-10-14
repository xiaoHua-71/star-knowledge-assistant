package com.xiaohua.star.model.enums;

/**
 * SSE 事件类型（{@code agent_event.eventType}，同时是推给前端的 {@code event:} 名）。见 frontend/02。
 *
 * <p>{@link #code()} 是落库与传输用的下划线串；枚举名是大写下划线，便于 Java 侧引用。
 */
public enum AgentEventTypeEnum {

    RUN_START("run_start"),
    TODO_UPDATE("todo_update"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    INTERRUPT("interrupt"),
    RUN_END("run_end"),
    ERROR("error"),
    MESSAGE_SNAPSHOT("message_snapshot");

    private final String code;

    AgentEventTypeEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
