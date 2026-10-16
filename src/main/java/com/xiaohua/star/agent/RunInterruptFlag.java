package com.xiaohua.star.agent;

import com.xiaohua.star.constant.CacheKey;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Agent 运行的中断标志（Redis）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p>key = {@code agent:interrupt:{runId}}，值 = {@link InterruptTypeEnum} 的名（缺省按 {@link InterruptTypeEnum#USER_STOP} 处理）。
 * <b>不设 TTL、必须显式删除</b>——否则会出现「用户点了停止、标志却自己过期了」。
 *
 * <p><b>为什么用 Redis 而不是内存 Map</b>：当前单实例内存 Map 也能工作，但 SSE 重连、
 * 将来多实例部署、以及「点停止的请求和跑着的运行不在同一实例」这三种情况都会让内存 Map 失效。
 * 中断是<b>协作式</b>的：不打断正在执行的线程，改由状态机在每个安全点主动来查这个标志。
 */
@Slf4j
@Component
public class RunInterruptFlag {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 登记一次中断请求（值即中断类型，供安全点判定挂起原因）。 */
    public void set(long runId, InterruptTypeEnum type) {
        stringRedisTemplate.opsForValue().set(key(runId), type.name());
    }

    /** 读中断标志；无则 {@link Optional#empty()}。 */
    public Optional<InterruptTypeEnum> get(long runId) {
        String v = stringRedisTemplate.opsForValue().get(key(runId));
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InterruptTypeEnum.valueOf(v));
        } catch (IllegalArgumentException e) {
            // 值坏了也不该阻断运行——按「用户停止」处理，最保守
            log.warn("中断标志值非法: runId={}, value={}", runId, v);
            return Optional.of(InterruptTypeEnum.USER_STOP);
        }
    }

    /** 运行已收敛（挂起 / 完成 / 失败）后清标志。 */
    public void clear(long runId) {
        stringRedisTemplate.delete(key(runId));
    }

    private static String key(long runId) {
        return CacheKey.AGENT_INTERRUPT.key(String.valueOf(runId));
    }
}
