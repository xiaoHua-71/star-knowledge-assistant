package com.xiaohua.star.ai;

import com.xiaohua.star.exception.BusinessException;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片器验证（M1）。
 *
 * <p>不启 Spring、不碰 Milvus——切片是纯函数，能在无外部依赖下验。
 * 它守的核心假设是：<b>中文长串不会让切片器抛异常</b>。官方的
 * {@code DocumentSplitters.recursive} 在末级 word 拆分器 subSplitter=null 时，
 * 遇到无空格无标点的长中文串会抛 RuntimeException；我们在末级接了字符拆分器兜底
 * （见 {@code ChunkSplitterFactory} 与 knowledge/22 坑 1）。这条断言就是防止有人
 * 把兜底那级删掉。
 */
class ChunkSplitterTest {

    private static final int MAX_CHARS = 400;
    private static final int OVERLAP = 80;

    private final ChunkSplitterFactory factory = new ChunkSplitterFactory();

    @Test
    void 无标点无空格的中文长串不会抛异常且不超上限() {
        // 3000 个「年」，没有换行、没有句号、没有空格——最刁钻的输入
        String text = "年".repeat(3000);

        List<TextSegment> segments = factory.split(text, MAX_CHARS, OVERLAP);

        assertFalse(segments.isEmpty(), "应当切出至少一片");
        for (TextSegment segment : segments) {
            assertTrue(segment.text().length() <= MAX_CHARS,
                    "切片长度超过上限 " + MAX_CHARS + "，实际 " + segment.text().length());
        }
        // 没有信息丢失大意：拼接后长度应不小于原文（重叠会让它更大）
        int total = segments.stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(total >= text.length(), "切片总长度小于原文，可能有内容丢失");
    }

    @Test
    void 带标点的中文正文在句末标点处切开() {
        String sentence = "员工入职满一年后可享受年假，年假天数随工龄增长。";
        String text = sentence.repeat(60); // 约 1560 字

        List<TextSegment> segments = factory.split(text, MAX_CHARS, OVERLAP);

        assertTrue(segments.size() > 1, "这么长的正文应该被切成多片");
        for (TextSegment segment : segments) {
            String s = segment.text();
            assertTrue(s.length() <= MAX_CHARS, "切片长度超过上限，实际 " + s.length());
        }
        // 除最后一片外，每片都应以句末标点结束——说明是沿句子边界切的，而不是把句子从中间劈开
        for (int i = 0; i < segments.size() - 1; i++) {
            String s = segments.get(i).text();
            assertTrue(s.endsWith("。"), "第 " + i + " 片没有在句末标点处结束: ..." + s.substring(s.length() - 5));
        }
    }

    /**
     * 守住一个<b>已知限制</b>：中文场景 chunkOverlap 实际不生效。
     *
     * <p>原因是 {@code HierarchicalDocumentSplitter.overlapFrom()} 写死了用 OpenNLP 英文断句模型，
     * 中文切不出整句 → 重叠为空。如果哪天这条断言失败了（总长度 > 原文），说明重叠开始生效了，
     * 那时该做的是更新 knowledge/22 的坑 2，而不是删掉这条断言。
     */
    @Test
    void 中文下重叠暂不生效_总长度等于原文() {
        String sentence = "员工入职满一年后可享受年假，年假天数随工龄增长。";
        String text = sentence.repeat(60);

        List<TextSegment> segments = factory.split(text, MAX_CHARS, OVERLAP);

        int total = segments.stream().mapToInt(s -> s.text().length()).sum();
        assertEquals(text.length(), total, "重叠意外生效了？请复核 ChunkSplitterFactory 与 knowledge/22");
    }

    @Test
    void 短文本不切且完整保留() {
        String text = "年假怎么算？";
        List<TextSegment> segments = factory.split(text, MAX_CHARS, OVERLAP);

        assertEquals(1, segments.size());
        assertEquals(text, segments.get(0).text());
    }

    @Test
    void 非法参数被拒绝() {
        assertThrows(BusinessException.class, () -> factory.split("x", 0, 0));
        assertThrows(BusinessException.class, () -> factory.split("x", 400, 400));
        assertThrows(BusinessException.class, () -> factory.split("x", 400, -1));
    }
}
