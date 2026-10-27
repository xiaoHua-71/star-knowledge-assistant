package com.xiaohua.star.ai;

import com.xiaohua.star.config.SearchProperties;

/**
 * 一次检索的完整参数。
 *
 * <p>生产路径由 {@link SearchProperties} 生成；离线评测在此基础上改一两个开关，
 * 用 {@link #withHybrid} / {@link #withRerank} 等生成变体。把参数做成不可变对象，
 * 避免评估期间改单例配置影响别的请求。</p>
 *
 * <p>{@code rerankAllowed} 只表示「允许精排」，真正是否发生还取决于评分模型 bean 是否存在、
 * 以及候选数是否大于 topK。报告里要同时记录「是否允许」和「模型是否可用」，
 * 否则默认关闭 rerank 时，no-rerank 变体会静默地等于 all-on。</p>
 */
public record SearchOptions(boolean hybridEnabled,
                            int vectorRecall,
                            int bm25Recall,
                            int rrfK,
                            int maxCandidates,
                            int maxPerSource,
                            double minScore,
                            boolean rerankAllowed,
                            int topK) {

    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 20;

    public SearchOptions {
        topK = clampTopK(topK);
    }

    /**
     * 以生产配置为底，只覆盖 topK。
     */
    public static SearchOptions from(SearchProperties properties, Integer topK) {
        return new SearchOptions(
                properties.isHybridEnabled(),
                properties.getVectorRecall(),
                properties.getBm25Recall(),
                properties.getRrfK(),
                properties.getMaxCandidates(),
                properties.getMaxPerSource(),
                properties.getMinScore(),
                true,
                topK == null ? DEFAULT_TOP_K : topK);
    }

    public static int clampTopK(int topK) {
        return Math.min(Math.max(topK, 1), MAX_TOP_K);
    }

    public SearchOptions withHybrid(boolean value) {
        return new SearchOptions(value, vectorRecall, bm25Recall, rrfK, maxCandidates,
                maxPerSource, minScore, rerankAllowed, topK);
    }

    public SearchOptions withRerank(boolean value) {
        return new SearchOptions(hybridEnabled, vectorRecall, bm25Recall, rrfK, maxCandidates,
                maxPerSource, minScore, value, topK);
    }

    public SearchOptions withMaxPerSource(int value) {
        return new SearchOptions(hybridEnabled, vectorRecall, bm25Recall, rrfK, maxCandidates,
                value, minScore, rerankAllowed, topK);
    }

    public SearchOptions withTopK(int value) {
        return new SearchOptions(hybridEnabled, vectorRecall, bm25Recall, rrfK, maxCandidates,
                maxPerSource, minScore, rerankAllowed, value);
    }

    public SearchOptions withMinScore(double value) {
        return new SearchOptions(hybridEnabled, vectorRecall, bm25Recall, rrfK, maxCandidates,
                maxPerSource, value, rerankAllowed, topK);
    }

    /** 同时覆盖向量与 BM25 两路召回数（标定用；两路口径不同，暂不拆开调）。 */
    public SearchOptions withRecall(int value) {
        return new SearchOptions(hybridEnabled, value, value, rrfK, maxCandidates,
                maxPerSource, minScore, rerankAllowed, topK);
    }

    /**
     * 写进评估报告的参数快照 —— 光看变体名说不出 topK 和召回数是多少。
     */
    public String describe() {
        return "hybrid=" + hybridEnabled
                + ", vectorRecall=" + vectorRecall
                + ", bm25Recall=" + bm25Recall
                + ", rrfK=" + rrfK
                + ", maxCandidates=" + maxCandidates
                + ", maxPerSource=" + maxPerSource
                + ", minScore=" + minScore
                + ", rerank=" + rerankAllowed
                + ", topK=" + topK;
    }
}
