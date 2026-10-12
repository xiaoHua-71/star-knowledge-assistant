package com.xiaohua.star.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * kbIds 逗号串 ↔ 列表 的相互转换（M4）。纯逻辑，不启 Spring。
 */
class ConversationKbIdsTest {

    @Test
    void 空列表或null格式化为null() {
        assertNull(ConversationServiceImpl.formatKbIds(null));
        assertNull(ConversationServiceImpl.formatKbIds(List.of()));
    }

    @Test
    void 全空项也归为null() {
        assertNull(ConversationServiceImpl.formatKbIds(Arrays.asList("", "  ", null)));
    }

    @Test
    void 列表格式化为逗号串() {
        assertEquals("1,2,3", ConversationServiceImpl.formatKbIds(List.of("1", "2", "3")));
    }

    @Test
    void 格式化去空白去空项去重() {
        assertEquals("1,2", ConversationServiceImpl.formatKbIds(Arrays.asList(" 1 ", "", "2", "1", null)));
    }

    @Test
    void 空串解析为空列表() {
        assertEquals(List.of(), ConversationServiceImpl.parseKbIds(null));
        assertEquals(List.of(), ConversationServiceImpl.parseKbIds("   "));
    }

    @Test
    void 逗号串解析去空白() {
        assertEquals(List.of("1", "2", "3"), ConversationServiceImpl.parseKbIds("1, 2 ,3"));
    }

    @Test
    void 往返一致() {
        List<String> input = List.of("1", "2");
        assertEquals(input, ConversationServiceImpl.parseKbIds(ConversationServiceImpl.formatKbIds(input)));
    }
}
