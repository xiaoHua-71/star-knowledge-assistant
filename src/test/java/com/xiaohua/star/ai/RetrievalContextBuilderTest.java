package com.xiaohua.star.ai;

import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;
import com.xiaohua.star.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索上下文构建：多库 round-robin 合并 + 格式化 + 失败隔离。纯逻辑，不启 Spring。
 */
class RetrievalContextBuilderTest {

    // ---------------- build()：编排（用 fake KnowledgeSearchService，非 Mockito） ----------------

    @Test
    void 空kbIds返回null() {
        RetrievalContextBuilder b = newBuilder((kbId, req, userId) -> resultOf(hit(1L, "d", "c")));

        assertNull(b.build(null, "q", 9L));
        assertNull(b.build(List.of(), "q", 9L));
    }

    @Test
    void 单库命中返回带来源与正文的上下文() {
        RetrievalContextBuilder b = newBuilder((kbId, req, userId) -> resultOf(hit(1L, "doc-1", "年假每年十天")));

        String ctx = b.build(List.of(1L), "年假多少天", 9L);

        assertNotNull(ctx);
        assertTrue(ctx.contains("来源：《doc-1》"));
        assertTrue(ctx.contains("年假每年十天"));
    }

    @Test
    void 某库抛异常被跳过其余正常() {
        RetrievalContextBuilder b = newBuilder((kbId, req, userId) -> {
            if (kbId == 1L) {
                throw new RuntimeException("kb 被删/越权");
            }
            return resultOf(hit(2L, "doc-2", "ok"));
        });

        String ctx = b.build(List.of(1L, 2L), "q", 9L);

        assertNotNull(ctx);
        assertTrue(ctx.contains("doc-2"));
        assertFalse(ctx.contains("doc-1"));
    }

    @Test
    void 全部失败或全部无命中返回null() {
        RetrievalContextBuilder throwing = newBuilder((kbId, req, userId) -> {
            throw new RuntimeException("boom");
        });
        assertNull(throwing.build(List.of(1L, 2L), "q", 9L));

        RetrievalContextBuilder empty = newBuilder((kbId, req, userId) -> resultOf());
        assertNull(empty.build(List.of(1L), "q", 9L));
    }

    // ---------------- interleave()：round-robin + 去重 + 上限 ----------------

    @Test
    void 交错按轮次取而非整段拼接() {
        List<List<KnowledgeSearchResultVO.Hit>> perKb = List.of(
                List.of(hit(1L, "A", "a1"), hit(2L, "A", "a2")),
                List.of(hit(3L, "B", "b1")));

        List<KnowledgeSearchResultVO.Hit> merged = RetrievalContextBuilder.interleave(perKb);

        // 先各库取第 1 名（1,3），再回头取 A 的第 2 名（2）
        assertEquals(List.of(1L, 3L, 2L), merged.stream().map(KnowledgeSearchResultVO.Hit::getChunkId).toList());
    }

    @Test
    void 跨库重复chunkId去重() {
        List<List<KnowledgeSearchResultVO.Hit>> perKb = List.of(
                List.of(hit(1L, "A", "a")),
                List.of(hit(1L, "B", "重复"), hit(2L, "B", "c")));

        List<KnowledgeSearchResultVO.Hit> merged = RetrievalContextBuilder.interleave(perKb);

        assertEquals(List.of(1L, 2L), merged.stream().map(KnowledgeSearchResultVO.Hit::getChunkId).toList());
    }

    @Test
    void 合并上限截断() {
        List<List<KnowledgeSearchResultVO.Hit>> perKb = List.of(
                List.of(hit(1L, "A", "a"), hit(2L, "A", "b"), hit(3L, "A", "c")),
                List.of(hit(4L, "B", "d"), hit(5L, "B", "e"), hit(6L, "B", "f")));

        assertEquals(5, RetrievalContextBuilder.interleave(perKb).size());
    }

    // ---------------- formatContext()：截断 ----------------

    @Test
    void 单条超长正文被截断() {
        String longContent = "x".repeat(900);
        String ctx = RetrievalContextBuilder.formatContext(List.of(hit(1L, "doc", longContent)));

        assertTrue(ctx.contains("x".repeat(800) + "…"));
        assertFalse(ctx.contains("x".repeat(801)));
    }

    // ---------------- 辅助 ----------------

    private static RetrievalContextBuilder newBuilder(KnowledgeSearchService svc) {
        RetrievalContextBuilder b = new RetrievalContextBuilder();
        ReflectionTestUtils.setField(b, "knowledgeSearchService", svc);
        return b;
    }

    private static KnowledgeSearchResultVO.Hit hit(Long chunkId, String docName, String content) {
        KnowledgeSearchResultVO.Hit h = new KnowledgeSearchResultVO.Hit();
        h.setChunkId(chunkId);
        h.setDocName(docName);
        h.setContent(content);
        return h;
    }

    private static KnowledgeSearchResultVO resultOf(KnowledgeSearchResultVO.Hit... hits) {
        KnowledgeSearchResultVO r = new KnowledgeSearchResultVO();
        r.setHits(List.of(hits));
        return r;
    }
}
