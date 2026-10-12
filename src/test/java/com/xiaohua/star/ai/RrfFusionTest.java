package com.xiaohua.star.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合验证（M3）。守的是「用排名而非分数融合」这条核心设计：多路都排前的片段要胜出。
 */
class RrfFusionTest {

    @Test
    void 只有一路时顺序保持不变() {
        Map<String, Double> fused = RrfFusion.newScoreMap();
        RrfFusion.accumulate(fused, List.of("a", "b", "c"), 60);

        assertEquals(List.of("a", "b", "c"), RrfFusion.topN(fused, 10));
    }

    @Test
    void 两路都排前的片段融合分最高() {
        Map<String, Double> fused = RrfFusion.newScoreMap();
        RrfFusion.accumulate(fused, List.of("a", "b"), 60); // a=1/61, b=1/62
        RrfFusion.accumulate(fused, List.of("b", "c"), 60); // b=+1/61, c=1/62

        // b 在两条路里都靠前，votes 叠加后应排第一
        assertEquals("b", RrfFusion.topN(fused, 3).get(0));
        assertEquals(3, fused.size(), "a/b/c 去重后共 3 个");
    }

    @Test
    void 空列表与空id被忽略() {
        Map<String, Double> fused = RrfFusion.newScoreMap();
        RrfFusion.accumulate(fused, null, 60);
        RrfFusion.accumulate(fused, List.of("", "   "), 60);
        RrfFusion.accumulate(fused, List.of("x"), 60);

        assertEquals(1, fused.size());
        assertEquals(List.of("x"), RrfFusion.topN(fused, 5));
    }

    @Test
    void topN截断_n非正返回空() {
        Map<String, Double> fused = RrfFusion.newScoreMap();
        RrfFusion.accumulate(fused, List.of("a", "b", "c"), 60);

        assertEquals(2, RrfFusion.topN(fused, 2).size());
        assertTrue(RrfFusion.topN(fused, 0).isEmpty());
    }

    @Test
    void k越小_头部相对优势越大() {
        Map<String, Double> small = RrfFusion.newScoreMap();
        RrfFusion.accumulate(small, List.of("a", "b"), 1);
        double gapSmall = small.get("a") - small.get("b");

        Map<String, Double> big = RrfFusion.newScoreMap();
        RrfFusion.accumulate(big, List.of("a", "b"), 60);
        double gapBig = big.get("a") - big.get("b");

        assertTrue(gapSmall > gapBig, "k 越小，第一名与第二名的差距应越大");
    }
}
