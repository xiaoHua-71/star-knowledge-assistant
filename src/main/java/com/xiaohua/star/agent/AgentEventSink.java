package com.xiaohua.star.agent;

/**
 * {@link AgentRunner} 的事件出口。循环只负责「产生了什么」，不关心「怎么送出去」。
 *
 * <p>适配层（SSE、未来的回放）实现本接口。M8 起，<b>里程碑事件先落库 {@code agent_event}、
 * 拿到自增 id，再经 {@link #onEvent} 推送</b>；{@code message_delta} 不落库、不带 id。
 */
public interface AgentEventSink {

    /**
     * 一个已落库的里程碑事件（run_start / tool_call / tool_result / run_end / error）。
     *
     * @param eventId 该事件的 {@code agent_event.id}，直接用作 SSE 的 {@code id}
     * @param eventName 事件名（对应 frontend/02 的 {@code event:}）
     * @param data    事件体（已是可直接序列化的对象；id 类字段已按契约转成字符串）
     */
    void onEvent(long eventId, String eventName, Object data);

    /** 逐 token 的回答增量。**不落库、无事件 id**——重连靠 {@code message_snapshot} 补偿（属 M9）。 */
    void onDelta(String delta);
}
