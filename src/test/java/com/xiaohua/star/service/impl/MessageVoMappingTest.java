package com.xiaohua.star.service.impl;

import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.vo.MessageVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息实体 → MessageVO 映射、以及「取最近一页后页内反转成正序」（M4）。纯逻辑，不启 Spring。
 *
 * <p>{@code toVO} / {@code ascendingWithinPage} 都不依赖注入的 bean，可直接 new。
 */
class MessageVoMappingTest {

    private final ConversationMessageServiceImpl service = new ConversationMessageServiceImpl();

    @Test
    void interrupted由0和1映射为布尔() {
        ConversationMessage one = new ConversationMessage();
        one.setInterrupted(1);
        ConversationMessage zero = new ConversationMessage();
        zero.setInterrupted(0);

        assertTrue(service.toVO(one).isInterrupted());
        assertFalse(service.toVO(zero).isInterrupted());
    }

    @Test
    void 字段正确映射且无工具时toolCalls为null() {
        ConversationMessage m = new ConversationMessage();
        m.setId(55L);
        m.setSeq(2);
        m.setRole("ASSISTANT");
        m.setContent("答案");
        m.setTokenCount(486);

        MessageVO vo = service.toVO(m);

        assertEquals(55L, vo.getId());
        assertEquals(2, vo.getSeq());
        assertEquals("ASSISTANT", vo.getRole());
        assertEquals("答案", vo.getContent());
        assertEquals(486, vo.getTokenCount());
        assertNull(vo.getToolCalls());
    }

    @Test
    void 最新在前翻转为正序() {
        List<MessageVO> newestFirst = List.of(vo(3), vo(2), vo(1));

        List<Integer> seqs = ConversationMessageServiceImpl.ascendingWithinPage(newestFirst)
                .stream().map(MessageVO::getSeq).toList();

        assertEquals(List.of(1, 2, 3), seqs);
    }

    @Test
    void 翻转返回新列表不改动入参() {
        List<MessageVO> input = new ArrayList<>(List.of(vo(3), vo(2), vo(1)));

        ConversationMessageServiceImpl.ascendingWithinPage(input);

        assertEquals(List.of(3, 2, 1), input.stream().map(MessageVO::getSeq).toList());
    }

    @Test
    void 单条或空页翻转不变() {
        assertEquals(List.of(1),
                ConversationMessageServiceImpl.ascendingWithinPage(List.of(vo(1)))
                        .stream().map(MessageVO::getSeq).toList());
        assertTrue(ConversationMessageServiceImpl.ascendingWithinPage(List.of()).isEmpty());
    }

    private static MessageVO vo(int seq) {
        MessageVO v = new MessageVO();
        v.setSeq(seq);
        return v;
    }
}
