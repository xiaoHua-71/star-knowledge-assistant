package com.xiaohua.star.service.impl;

import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源多样性验证（M3）：限制同一篇文档占 topK 的格数，但来源不足时用跳过的补齐。
 */
class SourceDiversityTest {

    private static KnowledgeSearchResultVO.Hit hit(long docId, int chunkIndex) {
        KnowledgeSearchResultVO.Hit hit = new KnowledgeSearchResultVO.Hit();
        hit.setDocId(docId);
        hit.setChunkIndex(chunkIndex);
        // 分数只用于排序展示，这里按传入顺序递减即可
        hit.setScore(1.0 - chunkIndex * 0.01);
        return hit;
    }

    @Test
    void 同一文档超过上限时由其它来源顶上() {
        List<KnowledgeSearchResultVO.Hit> ordered =
                List.of(hit(1, 0), hit(1, 1), hit(1, 2), hit(2, 0));

        List<KnowledgeSearchResultVO.Hit> picked = KnowledgeSearchServiceImpl.selectDiverse(ordered, 3, 2);

        assertEquals(3, picked.size());
        // 前两格给 doc1，第三格跳过 doc1 后由 doc2 顶上（而不是取 doc1 的第三段）
        assertEquals(2, picked.stream().filter(h -> h.getDocId() == 1).count());
        assertEquals(1, picked.stream().filter(h -> h.getDocId() == 2).count());
        assertEquals(2L, picked.get(2).getDocId().longValue());
    }

    @Test
    void 来源不足时用跳过的补齐_数量不缩水() {
        List<KnowledgeSearchResultVO.Hit> ordered =
                List.of(hit(1, 0), hit(1, 1), hit(1, 2));

        List<KnowledgeSearchResultVO.Hit> picked = KnowledgeSearchServiceImpl.selectDiverse(ordered, 3, 2);

        assertEquals(3, picked.size(), "只有一篇文档时仍应返回 3 个——宁可重复，不少给知识");
    }

    @Test
    void 每源上限为0或负数表示不限制() {
        List<KnowledgeSearchResultVO.Hit> ordered =
                List.of(hit(1, 0), hit(1, 1), hit(1, 2));

        List<KnowledgeSearchResultVO.Hit> picked = KnowledgeSearchServiceImpl.selectDiverse(ordered, 3, 0);

        assertEquals(3, picked.size());
        assertEquals(List.of(1L, 1L, 1L), picked.stream().map(KnowledgeSearchResultVO.Hit::getDocId).toList());
    }

    @Test
    void limit非正返回空() {
        assertTrue(KnowledgeSearchServiceImpl.selectDiverse(List.of(hit(1, 0)), 0, 2).isEmpty());
    }
}
