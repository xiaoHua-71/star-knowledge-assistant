package com.xiaohua.star.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 关键词索引验证（M3）。纯 Java，不启 Spring、不碰 Milvus。
 */
class Bm25IndexTest {

    private static Bm25Index.Doc doc(String vectorId, long docId, int chunkIndex, String text) {
        return new Bm25Index.Doc(vectorId, docId, chunkIndex, text);
    }

    @Test
    void 中文按相邻两字切成bigram() {
        assertEquals(List.of("缓存", "存穿", "穿透"), Bm25Index.tokenize("缓存穿透"));
    }

    @Test
    void 单个汉字也自成一个token() {
        assertEquals(List.of("年"), Bm25Index.tokenize("年"));
    }

    @Test
    void 英文数字按单词切并小写_标点空白丢弃() {
        assertEquals(List.of("seata", "2024"), Bm25Index.tokenize("Seata, 2024！"));
    }

    @Test
    void 空查询_空索引_非正topN_都返回空() {
        Bm25Index index = new Bm25Index();
        // 空索引
        assertTrue(index.search("年假", 3).isEmpty());

        index.rebuild(List.of(doc("v1", 1, 0, "年假")));
        assertTrue(index.search("", 3).isEmpty());
        assertTrue(index.search("   ", 3).isEmpty());
        assertTrue(index.search("年假", 0).isEmpty());
    }

    @Test
    void 只返回字面命中的切片() {
        Bm25Index index = new Bm25Index();
        index.rebuild(List.of(
                doc("v1", 1, 0, "年假天数随工龄增长"),
                doc("v2", 1, 1, "报销需要合规发票"),
                doc("v3", 2, 0, "年假申请需提前三天")));

        List<Bm25Index.Doc> hits = index.search("年假", 10);

        assertEquals(2, hits.size(), "只有含「年假」的两段应命中");
        for (Bm25Index.Doc hit : hits) {
            assertTrue(hit.text().contains("年假"));
        }
    }

    @Test
    void 无字面命中的查询返回空() {
        Bm25Index index = new Bm25Index();
        index.rebuild(List.of(
                doc("v1", 1, 0, "年假天数"),
                doc("v2", 1, 1, "报销发票")));

        assertTrue(index.search("今天天气", 3).isEmpty());
    }

    @Test
    void 词频高的切片排更前() {
        Bm25Index index = new Bm25Index();
        index.rebuild(List.of(
                doc("v1", 1, 0, "年假 年假 年假"),
                doc("v2", 1, 1, "年假")));

        assertEquals("v1", index.search("年假", 2).get(0).vectorId());
    }

    @Test
    void 重建为空列表会清空索引() {
        Bm25Index index = new Bm25Index();
        index.rebuild(List.of(doc("v1", 1, 0, "年假")));
        assertEquals(1, index.size());

        index.rebuild(List.of());

        assertEquals(0, index.size());
        assertTrue(index.search("年假", 3).isEmpty());
    }
}
