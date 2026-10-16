package com.xiaohua.star.service;

import com.xiaohua.star.model.vo.AgentRunVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 运行的对外操作（中断 / 续跑 / 查询 / 重连）。见 frontend/40-agent-中断与恢复.md。
 *
 * <p>⚠️ 前端契约里最容易混淆的一对：<b>重连</b>（{@link #reconnect}，纯读、幂等）与
 * <b>继续</b>（{@link #resume}，改状态、会接着执行）。详见 knowledge/12 坑 4。
 */
public interface AgentRunService {

    /** 登记停止请求：RUNNING/PENDING 的运行置 Redis 中断标志（下一个安全点真正挂起）。返回被受理。 */
    boolean interrupt(long runId, Long userId);

    /** 从挂起点继续执行；校验归属与状态、重建消息、把循环重新提交到线程池，返回新的运行状态。 */
    AgentRunVO resume(long runId, Long userId);

    /** 查询运行状态（归属校验）。 */
    AgentRunVO getRun(long runId, Long userId);

    /**
     * 断线重连：补发 {@code id > lastEventId} 的已持久化事件；若运行还在跑，再挂上新流继续推。
     * 这是<b>纯读、幂等</b>的操作。
     */
    SseEmitter reconnect(long runId, Long userId, Long lastEventId);
}
