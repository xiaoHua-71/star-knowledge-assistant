package com.xiaohua.star.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.model.entity.AgentEvent;
import com.xiaohua.star.service.AgentStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 运行事件的实时出口（把「一次运行的推流」与「当前挂着哪个客户端」解耦）。见 knowledge/12、knowledge/31。
 *
 * <p>一个 {@code runId} 一个 {@link Channel}。通道持有一把锁、当前 emitter（带去重游标 {@code lastSentId}）、
 * 一个 delta 累积缓冲。{@link #pushEvent}/{@link #pushDelta} 由运行线程调用；{@link #attach} 由重连请求调用。
 *
 * <p><b>为什么不会重、不会漏</b>：{@code AgentRunner} 里「先落库 {@code agent_event}、再推」，
 * 而 {@link #attach} 在<b>同一把锁内</b>「补发库里 {@code id > lastEventId} 的事件 → 发 {@code message_snapshot} → 换 emitter」，
 * push 也在锁内按游标跳过已发过的 id。delta 无 id、不落库（见 knowledge/31 坑 3），靠缓冲快照补偿。
 *
 * <p>客户端断开不影响运行：运行线程只往「当前 emitter」推，断了就把该 emitter 标死、后续推送静默跳过，
 * 事件仍照常落库——重连时补发即可。
 */
@Slf4j
@Component
public class AgentStreamHub {

    /** 仅用于把库里存的 dataJson 还原成对象再推；存储的是单行 JSON。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AgentStateService agentStateService;

    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();

    /** 绑定到某运行的 {@link AgentEventSink}：循环产出的里程碑事件与 delta 都经此进通道。 */
    public AgentEventSink sink(long runId) {
        return new AgentEventSink() {
            @Override
            public void onEvent(long eventId, String eventName, Object data) {
                pushEvent(runId, eventId, eventName, data);
            }

            @Override
            public void onDelta(String delta) {
                pushDelta(runId, delta);
            }
        };
    }

    /** 首次建流时登记通道（{@code emitter} 可为 null——续跑时前端稍后才接流）。 */
    public void create(long runId, SseEmitter emitter) {
        Channel ch = new Channel();
        if (emitter != null) {
            ch.current = new EmitterRef(emitter, 0);
            ch.refs.add(ch.current);
        }
        channels.put(runId, ch);
    }

    /** 推一个已落库的里程碑事件（{@code eventId} 即去重游标）。通道不存在则静默跳过。 */
    public void pushEvent(long runId, long eventId, String eventName, Object data) {
        Channel ch = channels.get(runId);
        if (ch == null) {
            return;
        }
        ch.lock.lock();
        try {
            EmitterRef ref = ch.current;
            if (ref != null) {
                sendEvent(ref, eventId, eventName, data);
            }
        } finally {
            ch.lock.unlock();
        }
    }

    /** 推一段回答增量（不落库、无 id）；同时累积进缓冲，供重连快照。 */
    public void pushDelta(long runId, String delta) {
        Channel ch = channels.get(runId);
        if (ch == null) {
            return;
        }
        ch.lock.lock();
        try {
            ch.deltaBuf.append(delta);
            EmitterRef ref = ch.current;
            if (ref != null) {
                sendRaw(ref, null, "message_delta", Map.of("delta", delta));
            }
        } finally {
            ch.lock.unlock();
        }
    }

    /**
     * 重连：把新 emitter 接到还在跑的通道上。
     *
     * @return true = 通道还在（补发+快照后继续推新事件）；false = 已无活通道（调用方改走「纯库里补发」）
     */
    public boolean attach(long runId, SseEmitter emitter, long lastEventId) {
        Channel ch = channels.get(runId);
        if (ch == null) {
            return false;
        }
        ch.lock.lock();
        try {
            if (ch.finished) {
                return false;
            }
            EmitterRef ref = new EmitterRef(emitter, lastEventId);
            for (AgentEvent ev : agentStateService.listEventsAfter(runId, lastEventId)) {
                sendEvent(ref, ev.getId(), ev.getEventType(), parse(ev.getDataJson()));
            }
            String text = ch.deltaBuf.toString();
            if (!text.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("messageId", null);
                d.put("text", text);
                sendRaw(ref, null, "message_snapshot", d);
            }
            ch.refs.add(ref);
            ch.current = ref;
            return true;
        } finally {
            ch.lock.unlock();
        }
    }

    /** 运行收敛（完成/失败/挂起）：完成所有 emitter、摘除通道。 */
    public void finish(long runId) {
        Channel ch = channels.remove(runId);
        if (ch == null) {
            return;
        }
        ch.lock.lock();
        try {
            ch.finished = true;
            ch.current = null;
            for (EmitterRef ref : ch.refs) {
                ref.dead = true;
                try {
                    ref.emitter.complete();
                } catch (Exception ignored) {
                    // emitter 已完成 / 已断：无所谓
                }
            }
        } finally {
            ch.lock.unlock();
        }
    }

    /** 该 runId 是否还有活通道（重连兜底判定用）。 */
    public boolean hasChannel(long runId) {
        Channel ch = channels.get(runId);
        return ch != null && !ch.finished;
    }

    // ---------------- 内部 ----------------

    /** 按游标去重后推一个带 id 的事件；成功才推进游标。 */
    private void sendEvent(EmitterRef ref, long eventId, String eventName, Object data) {
        if (eventId <= ref.lastSentId) {
            return;
        }
        if (sendRaw(ref, eventId, eventName, data)) {
            ref.lastSentId = eventId;
        }
    }

    private boolean sendRaw(EmitterRef ref, Long id, String event, Object data) {
        if (ref.dead) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (id != null) {
                builder.id(String.valueOf(id));
            }
            builder.name(event).data(data, MediaType.APPLICATION_JSON);
            ref.emitter.send(builder.build());
            return true;
        } catch (IOException e) {
            ref.dead = true;
            return false;
        } catch (IllegalStateException e) {
            // emitter 已 complete：收尾竞态，视为该 emitter 已死
            ref.dead = true;
            return false;
        }
    }

    private static Object parse(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(dataJson, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static final class EmitterRef {
        final SseEmitter emitter;
        volatile long lastSentId;
        volatile boolean dead;

        EmitterRef(SseEmitter emitter, long lastSentId) {
            this.emitter = emitter;
            this.lastSentId = lastSentId;
        }
    }

    private static final class Channel {
        final ReentrantLock lock = new ReentrantLock();
        final StringBuilder deltaBuf = new StringBuilder();
        final List<EmitterRef> refs = new ArrayList<>();
        EmitterRef current;
        boolean finished;
    }
}
