package com.xiaohua.star.service.impl;

import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.entity.Conversation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流式对话的 kbIds 解析：请求覆盖 / 否则用会话关联 / 空数组不检索（frontend/30 契约）。
 */
class ChatStreamKbIdsTest {

    @Test
    void 请求未带kbIds时用会话关联() {
        assertEquals(List.of(1L, 2L), ChatStreamServiceImpl.resolveKbIds(req(null), conv("1,2")));
    }

    @Test
    void 请求带kbIds时覆盖会话() {
        assertEquals(List.of(3L), ChatStreamServiceImpl.resolveKbIds(req(List.of("3")), conv("1,2")));
    }

    @Test
    void 请求传空数组表示不用RAG() {
        assertEquals(List.of(), ChatStreamServiceImpl.resolveKbIds(req(List.of()), conv("1,2")));
    }

    @Test
    void 会话未关联且请求未带返回空() {
        assertEquals(List.of(), ChatStreamServiceImpl.resolveKbIds(req(null), conv(null)));
    }

    @Test
    void 非法kbId跳过() {
        assertEquals(List.of(3L, 4L),
                ChatStreamServiceImpl.resolveKbIds(req(List.of("3", "x", "  ", "4")), conv(null)));
    }

    private static ChatMessageRequest req(List<String> kbIds) {
        ChatMessageRequest r = new ChatMessageRequest();
        r.setKbIds(kbIds);
        return r;
    }

    private static Conversation conv(String kbIds) {
        Conversation c = new Conversation();
        c.setKbIds(kbIds);
        return c;
    }
}
