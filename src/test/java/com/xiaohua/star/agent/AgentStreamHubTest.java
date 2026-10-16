package com.xiaohua.star.agent;

import com.xiaohua.star.model.entity.AgentEvent;
import com.xiaohua.star.service.AgentStateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 断线重连的实时通道（M9）：补发不重不漏、快照、finish 收尾。见 knowledge/31。
 *
 * <p>用自定义 {@link SseEmitter} 捕获「推了什么」，不启 Spring、不连 Redis/DB。
 */
class AgentStreamHubTest {

    private static final long RUN_ID = 100L;

    @Test
    void 重连补发已持久化事件_并补部分文本快照_不重复() {
        AgentStateService state = mock(AgentStateService.class);
        when(state.listEventsAfter(RUN_ID, 1L)).thenReturn(List.of(
                event(2L, "tool_call", "{\"stepId\":\"2\"}"),
                event(3L, "tool_result", "{\"stepId\":\"2\"}")));
        AgentStreamHub hub = hub(state);

        CapturingEmitter first = new CapturingEmitter();
        hub.create(RUN_ID, first);
        hub.pushEvent(RUN_ID, 1L, "run_start", Map.of("runId", "100"));
        hub.pushDelta(RUN_ID, "你好");

        assertTrue(ids(first.frames).contains(1L));
        assertTrue(hasText(payloads(first.frames), "你好"));

        CapturingEmitter second = new CapturingEmitter();
        assertTrue(hub.attach(RUN_ID, second, 1L));

        assertTrue(ids(second.frames).containsAll(List.of(2L, 3L)), "应补发 id>lastEventId 的事件");
        assertFalse(ids(second.frames).contains(1L), "已发过的事件不应重复补发");
        assertTrue(hasText(payloads(second.frames), "你好"), "应补发当前部分文本快照");

        // 换 emitter 后，新事件只推给当前 emitter
        int firstBefore = first.frames.size();
        hub.pushEvent(RUN_ID, 4L, "run_end", Map.of("runId", "100"));
        assertTrue(ids(second.frames).contains(4L), "新事件应推给当前 emitter");
        assertEquals(firstBefore, first.frames.size(), "旧 emitter 不再接收新事件");

        hub.finish(RUN_ID);
        assertTrue(first.completed);
        assertTrue(second.completed);
        assertFalse(hub.hasChannel(RUN_ID));
    }

    @Test
    void 游标跳过已补发的重复id() {
        AgentStateService state = mock(AgentStateService.class);
        when(state.listEventsAfter(RUN_ID, 5L)).thenReturn(List.of());
        AgentStreamHub hub = hub(state);
        hub.create(RUN_ID, null);   // 续跑场景：先建通道，稍后才接流

        CapturingEmitter emitter = new CapturingEmitter();
        assertTrue(hub.attach(RUN_ID, emitter, 5L));
        hub.pushEvent(RUN_ID, 5L, "x", Map.of());            // <= 游标 5 → 跳过
        hub.pushEvent(RUN_ID, 6L, "y", Map.of("k", "v"));    // > 游标 → 推

        assertEquals(List.of(6L), ids(emitter.frames));
    }

    @Test
    void 无活通道时attach返回false() {
        AgentStreamHub hub = hub(mock(AgentStateService.class));
        assertFalse(hub.attach(999L, new CapturingEmitter(), 0L));
    }

    @Test
    void 通道不存在时推送静默跳过() {
        AgentStreamHub hub = hub(mock(AgentStateService.class));
        hub.pushEvent(999L, 1L, "run_start", Map.of());   // 不应抛异常
        hub.pushDelta(999L, "x");
        assertFalse(hub.hasChannel(999L));
    }

    // ---------------- 辅助 ----------------

    private static AgentStreamHub hub(AgentStateService state) {
        AgentStreamHub hub = new AgentStreamHub();
        ReflectionTestUtils.setField(hub, "agentStateService", state);
        return hub;
    }

    private static AgentEvent event(long id, String type, String json) {
        AgentEvent e = new AgentEvent();
        e.setId(id);
        e.setRunId(RUN_ID);
        e.setConversationId(1L);
        e.setEventType(type);
        e.setDataJson(json);
        return e;
    }

    /**
     * 从捕获帧里抽 event id。SseEmitter 把 {@code id:}/{@code event:}/{@code data:} 前缀拼成**一个**字符串条目
     * （形如 {@code "id:1\nevent:run_start\ndata:"}），所以要按行拆。
     */
    private static List<Long> ids(List<Set<DataWithMediaType>> frames) {
        List<Long> out = new ArrayList<>();
        for (Set<DataWithMediaType> f : frames) {
            for (DataWithMediaType d : f) {
                if (d.getData() instanceof String s) {
                    for (String line : s.split("\n")) {
                        if (line.startsWith("id:")) {
                            try {
                                out.add(Long.parseLong(line.substring(3).trim()));
                            } catch (NumberFormatException ignored) {
                                // 非数字 id 跳过
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<Object> payloads(List<Set<DataWithMediaType>> frames) {
        List<Object> out = new ArrayList<>();
        for (Set<DataWithMediaType> f : frames) {
            for (DataWithMediaType d : f) {
                if (!(d.getData() instanceof String)) {
                    out.add(d.getData());
                }
            }
        }
        return out;
    }

    private static boolean hasText(List<Object> payloads, String text) {
        for (Object o : payloads) {
            if (o instanceof Map<?, ?> m && m.containsValue(text)) {
                return true;
            }
        }
        return false;
    }

    /** 捕获每次 send 的帧，并记录完成状态。 */
    static class CapturingEmitter extends SseEmitter {
        final List<Set<DataWithMediaType>> frames = new ArrayList<>();
        boolean completed = false;

        @Override
        public void send(Set<DataWithMediaType> dataToSend) throws IOException {
            frames.add(dataToSend);
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }
    }
}
