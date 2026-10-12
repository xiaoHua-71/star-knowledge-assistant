package com.xiaohua.star.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量索引的切片 diff 验证（M3 / knowledge/24）。纯 Java，不启 Spring、不碰 Milvus。
 */
class IndexDiffTest {

    @Test
    void 首次索引_旧集为空_全部新增() {
        IndexDiff.Result result = IndexDiff.diff(List.of(), List.of("a", "b", "c"));

        assertEquals(List.of("a", "b", "c"), result.toInsert());
        assertTrue(result.toDelete().isEmpty());
        assertEquals(0, result.unchanged());
        assertTrue(!result.noChange());
    }

    @Test
    void 内容完全没变_无新增无删除_可整体跳过() {
        IndexDiff.Result result = IndexDiff.diff(List.of("a", "b", "c"), List.of("a", "b", "c"));

        assertTrue(result.toInsert().isEmpty());
        assertTrue(result.toDelete().isEmpty());
        assertEquals(3, result.unchanged());
        assertTrue(result.noChange(), "无变化时应能整体跳过");
    }

    @Test
    void 只改了一部分_新增与删除各归各位() {
        // 旧: a b c ；新: a c d  → b 消失、d 新增、a/c 保留
        IndexDiff.Result result = IndexDiff.diff(List.of("a", "b", "c"), List.of("a", "c", "d"));

        assertEquals(List.of("d"), result.toInsert());
        assertEquals(List.of("b"), result.toDelete());
        assertEquals(2, result.unchanged());
    }

    @Test
    void 内容全换_全新增全删除() {
        IndexDiff.Result result = IndexDiff.diff(List.of("a", "b"), List.of("x", "y", "z"));

        assertEquals(List.of("x", "y", "z"), result.toInsert());
        assertEquals(Set.of("a", "b"), Set.copyOf(result.toDelete()));
        assertEquals(0, result.unchanged());
    }

    @Test
    void toInsert保持新集合的顺序_以便与embedding结果一一对应() {
        IndexDiff.Result result = IndexDiff.diff(List.of("a"), List.of("z", "y", "x"));

        assertEquals(List.of("z", "y", "x"), result.toInsert());
    }

    @Test
    void 新集合为空_全部删除() {
        IndexDiff.Result result = IndexDiff.diff(List.of("a", "b"), List.of());

        assertTrue(result.toInsert().isEmpty());
        assertEquals(Set.of("a", "b"), Set.copyOf(result.toDelete()));
        assertEquals(0, result.unchanged());
        assertTrue(!result.noChange(), "有删除也算变化，不能跳过");
    }

    @Test
    void null入参按空处理不抛异常() {
        assertEquals(0, IndexDiff.diff(null, null).unchanged());
        assertTrue(IndexDiff.diff(null, List.of("a")).toInsert().equals(List.of("a")));
        assertEquals(Set.of("a"), Set.copyOf(IndexDiff.diff(List.of("a"), null).toDelete()));
    }
}
