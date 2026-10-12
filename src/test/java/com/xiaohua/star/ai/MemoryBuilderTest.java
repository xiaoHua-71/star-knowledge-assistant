package com.xiaohua.star.ai;

import com.xiaohua.star.model.entity.ConversationMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 记忆重建：组装规则 + 预算裁剪 + token 估算（M5）。纯逻辑，不启 Spring。
 *
 * <p>系数取 1.0，便于按字符数直接推算 token。
 */
class MemoryBuilderTest {

    private static final double COEF = 1.0;

    @Test
    void 空历史只产出系统消息() {
        List<ChatMessage> messages = MemoryBuilder.assemble(List.of(), "SYS", 1000, COEF);

        assertEquals(1, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals("SYS", ((SystemMessage) messages.get(0)).text());
    }

    @Test
    void 首条恒为系统消息且角色映射保序() {
        List<ConversationMessage> asc = List.of(
                msg(1, "USER", "u1"),
                msg(2, "ASSISTANT", "a1"),
                msg(3, "USER", "u2"));

        List<ChatMessage> messages = MemoryBuilder.assemble(asc, "SYS", 10000, COEF);

        assertEquals(4, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertInstanceOf(AiMessage.class, messages.get(2));
        assertInstanceOf(UserMessage.class, messages.get(3));
        assertEquals("u2", ((UserMessage) messages.get(3)).singleText());
    }

    @Test
    void ASSISTANT只取content忽略toolCallsJson() {
        ConversationMessage a = new ConversationMessage();
        a.setSeq(2);
        a.setRole("ASSISTANT");
        a.setContent("答案");
        a.setToolCallsJson("[{\"function\":{\"name\":\"x\"}}]");

        List<ChatMessage> messages = MemoryBuilder.assemble(List.of(a), "SYS", 10000, COEF);

        assertEquals("答案", ((AiMessage) messages.get(1)).text());
    }

    @Test
    void 预算充足时全部保留() {
        List<ConversationMessage> asc = List.of(
                msg(1, "USER", "aaaa"),
                msg(2, "ASSISTANT", "bbbb"));

        List<ChatMessage> messages = MemoryBuilder.assemble(asc, "SYS", 100, COEF);

        assertEquals(3, messages.size());
    }

    @Test
    void 预算不足时只保留最近的一段() {
        // 字符数即 token（系数 1）：seq1=10, seq2=5, seq3=3；预算 10
        List<ConversationMessage> asc = List.of(
                msg(1, "USER", "1111111111"),
                msg(2, "ASSISTANT", "22222"),
                msg(3, "USER", "333"));

        List<ChatMessage> messages = MemoryBuilder.assemble(asc, "SYS", 10, COEF);

        // 从最新往回：3 → 8 → 再加 10 超出，停止。保留的是最近两条（seq2,seq3），升序
        assertEquals(3, messages.size());
        assertEquals("22222", ((AiMessage) messages.get(1)).text());
        assertEquals("333", ((UserMessage) messages.get(2)).singleText());
    }

    @Test
    void 最新一条即使超预算也保留() {
        List<ConversationMessage> asc = List.of(
                msg(1, "USER", "x"),
                msg(2, "USER", "y".repeat(100)));

        List<ChatMessage> messages = MemoryBuilder.assemble(asc, "SYS", 5, COEF);

        assertEquals(2, messages.size());
        assertEquals(100, ((UserMessage) messages.get(1)).singleText().length());
    }

    @Test
    void 估算按字符数乘系数向上取整() {
        assertEquals(0, MemoryBuilder.estimateTokens(null, 1.0));
        assertEquals(0, MemoryBuilder.estimateTokens("", 1.0));
        assertEquals(3, MemoryBuilder.estimateTokens("abc", 1.0));
        assertEquals(3, MemoryBuilder.estimateTokens("ab", 1.5));
        assertEquals(2, MemoryBuilder.estimateTokens("a", 1.5));
    }

    private static ConversationMessage msg(int seq, String role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
