package com.xiaohua.star.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）：把多路召回的结果合成一个排序。
 *
 * <p>{@code score(d) = Σ 1 / (k + rank_i(d))}，rank 从 1 开始，k 是平滑常数（默认 60）。
 * 用<b>排名</b>而不是分数来融合，是因为各路分数的量纲完全不同——
 * 向量余弦在 [-1, 1]、BM25 分无上界、未来若加别的召回来源又是另一套尺度。
 * 先把它们归一化再调权重，参数多且难调；RRF 只看「谁排在第几」，天然免疫量纲，无需调参。
 *
 * <p>副作用是「多路共识」：在越多路里都排得靠前的片段，累加后分数越高——
 * 相当于让每路投一票。
 *
 * <p>抽成独立无状态工具类，是为了能脱离 Spring 与向量库单测（见 {@code RetrievalFusionTest}）。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * 把一路「按相关性有序的 id 列表」累加进融合分数表（原地修改 {@code fused}）。
     *
     * @param fused     分数表：id → 融合分。多路共用同一张表
     * @param rankedIds 某一路的召回结果，按相关性降序；下标即排名
     * @param k         平滑常数，越大越削弱「某一路排第一」的叠加优势
     */
    public static void accumulate(Map<String, Double> fused, List<String> rankedIds, int k) {
        if (rankedIds == null) {
            return;
        }
        for (int i = 0; i < rankedIds.size(); i++) {
            String id = rankedIds.get(i);
            if (id == null || id.isBlank()) {
                continue;
            }
            fused.merge(id, 1.0 / (k + i + 1), Double::sum);
        }
    }

    /**
     * 按融合分降序取前 n 个 id。分数相同时保持插入顺序（{@code LinkedHashMap} + 稳定排序）。
     */
    public static List<String> topN(Map<String, Double> fused, int n) {
        if (n <= 0 || fused.isEmpty()) {
            return List.of();
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 便捷方法：新建一张（保持插入顺序的）分数表
     */
    public static Map<String, Double> newScoreMap() {
        return new LinkedHashMap<>();
    }
}
