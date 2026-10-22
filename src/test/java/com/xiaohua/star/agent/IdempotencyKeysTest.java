package com.xiaohua.star.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等键规范化（M10）：键序/空白/数值格式不能影响键值，否则唯一索引防线静默失效。
 * 见 knowledge/12 坑 2。
 */
class IdempotencyKeysTest {

    @Test
    void 键顺序不同的等价参数得到同一个键() {
        String a = IdempotencyKeys.of(100, 2, "createTicket",
                "{\"title\":\"年假咨询\",\"assignee\":\"hr\"}");
        String b = IdempotencyKeys.of(100, 2, "createTicket",
                "{\"assignee\":\"hr\",\"title\":\"年假咨询\"}");
        assertEquals(a, b);
    }

    @Test
    void 空白与数值格式被规范化() {
        String a = IdempotencyKeys.of(100, 2, "createTicket",
                "{ \"topK\" : 5, \"nested\": { \"b\": 2, \"a\": 1 } }");
        String b = IdempotencyKeys.of(100, 2, "createTicket",
                "{\"topK\":5.0,\"nested\":{\"a\":1,\"b\":2.00}}");
        assertEquals(a, b);
    }

    @Test
    void 运行_轮次_工具名任一不同则键不同() {
        String base = IdempotencyKeys.of(100, 2, "createTicket", "{\"title\":\"x\"}");
        assertNotEquals(base, IdempotencyKeys.of(101, 2, "createTicket", "{\"title\":\"x\"}"));
        assertNotEquals(base, IdempotencyKeys.of(100, 3, "createTicket", "{\"title\":\"x\"}"));
        assertNotEquals(base, IdempotencyKeys.of(100, 2, "otherTool", "{\"title\":\"x\"}"));
        assertNotEquals(base, IdempotencyKeys.of(100, 2, "createTicket", "{\"title\":\"y\"}"));
    }

    @Test
    void 空参数与非法JSON都有稳定兜底() {
        assertEquals(IdempotencyKeys.of(1, 1, "t", null), IdempotencyKeys.of(1, 1, "t", "{}"));
        assertEquals(IdempotencyKeys.of(1, 1, "t", "not-json"),
                IdempotencyKeys.of(1, 1, "t", " not-json "));
    }

    @Test
    void 键为32位十六进制() {
        String key = IdempotencyKeys.of(1, 1, "t", "{}");
        assertEquals(32, key.length());
        assertEquals(true, key.matches("[0-9a-f]{32}"));
    }
}
