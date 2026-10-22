package com.xiaohua.star.agent;

import com.xiaohua.star.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 心跳（M13）。每 {@code ai.agent.sse-ping-interval-ms} 推一个 {@code ping} 事件：
 * 既保活中间代理，也让前端能确认连接没死（见 frontend/02 事件表）。
 *
 * <p>{@code ping} 不落库、没有 id，不参与断线补发。调用方必须在
 * {@code onCompletion/onTimeout/onError} 里调用返回的 stop 句柄（这些回调是单值字段，
 * 后注册会覆盖先注册，所以本类不自己注册回调）。
 */
@Slf4j
@Component
public class SseHeartbeat {

    @Resource
    private AgentProperties agentProperties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * 为某个 emitter 启动心跳。
     *
     * @return 停止心跳的句柄；心跳关闭时是空操作
     */
    public Runnable start(SseEmitter emitter) {
        long interval = agentProperties.getSsePingIntervalMs();
        if (interval <= 0) {
            return () -> {
            };
        }
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        future.set(scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("ping")
                        .data(Map.of(), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 客户端已断开 / emitter 已完成：停掉这条心跳，避免泄漏
                ScheduledFuture<?> f = future.get();
                if (f != null) {
                    f.cancel(false);
                }
            }
        }, interval, interval, TimeUnit.MILLISECONDS));

        return () -> {
            ScheduledFuture<?> f = future.get();
            if (f != null) {
                f.cancel(false);
            }
        };
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
