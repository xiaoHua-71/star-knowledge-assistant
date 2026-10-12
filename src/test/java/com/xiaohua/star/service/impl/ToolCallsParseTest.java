package com.xiaohua.star.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * toolCallsJson → 工具名列表 的提取（M4）。纯逻辑，不启 Spring。
 */
class ToolCallsParseTest {

    @Test
    void null或空白返回null() {
        assertNull(ConversationMessageServiceImpl.parseToolCalls(null));
        assertNull(ConversationMessageServiceImpl.parseToolCalls("  "));
    }

    @Test
    void OpenAI工具调用数组提取函数名() {
        String json = "[{\"id\":\"a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"searchKnowledge\",\"arguments\":\"{}\"}}]";
        assertEquals(List.of("searchKnowledge"), ConversationMessageServiceImpl.parseToolCalls(json));
    }

    @Test
    void 纯字符串数组原样返回() {
        assertEquals(List.of("searchKnowledge", "createTicket"),
                ConversationMessageServiceImpl.parseToolCalls("[\"searchKnowledge\",\"createTicket\"]"));
    }

    @Test
    void 非法JSON返回null不抛异常() {
        assertNull(ConversationMessageServiceImpl.parseToolCalls("{oops"));
    }

    @Test
    void 非数组返回null() {
        assertNull(ConversationMessageServiceImpl.parseToolCalls("{\"a\":1}"));
    }
}
