package com.xiaohua.star.agent;

import com.xiaohua.star.model.enums.InterruptTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中断标志（M9）：key 形态、值语义、坏值兜底。见 knowledge/12。
 */
class RunInterruptFlagTest {

    @Test
    void 使用预期的key与值() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        RunInterruptFlag flag = new RunInterruptFlag();
        ReflectionTestUtils.setField(flag, "stringRedisTemplate", redis);

        flag.set(100L, InterruptTypeEnum.USER_STOP);
        verify(ops).set("agent:interrupt:100", "USER_STOP");

        when(ops.get("agent:interrupt:100")).thenReturn("NEW_MESSAGE");
        assertEquals(Optional.of(InterruptTypeEnum.NEW_MESSAGE), flag.get(100L));

        when(ops.get("agent:interrupt:100")).thenReturn(null);
        assertEquals(Optional.empty(), flag.get(100L));

        flag.clear(100L);
        verify(redis).delete("agent:interrupt:100");
    }

    @Test
    void 标志值非法时按用户停止处理() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("agent:interrupt:100")).thenReturn("garbage");
        RunInterruptFlag flag = new RunInterruptFlag();
        ReflectionTestUtils.setField(flag, "stringRedisTemplate", redis);

        assertEquals(Optional.of(InterruptTypeEnum.USER_STOP), flag.get(100L));
    }
}
