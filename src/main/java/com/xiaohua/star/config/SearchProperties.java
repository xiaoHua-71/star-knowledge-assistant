package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索链路参数（application.yml 的 knowledge.search.*）
 *
 * <p>这套参数从 salary-leap 移植过来，但<b>语料不同，一律标「未标定」</b>——
 * 它们是能跑的起点，不是最优值。要定最优值必须先有评测集（见 knowledge/60）。
 */
@Component
@ConfigurationProperties(prefix = "knowledge.search")
@Data
public class SearchProperties {

    /**
     * 是否开启混合检索（BM25 + 向量双路召回）。关掉就退化成 M1 的纯向量检索。
     */
    private boolean hybridEnabled = true;

    /**
     * 向量这一路召回多少候选（未标定）
     */
    private int vectorRecall = 20;

    /**
     * BM25 这一路召回多少候选（未标定）
     */
    private int bm25Recall = 20;

    /**
     * RRF 平滑常数，原论文默认 60。越大越削弱「某一路排第一」的叠加优势（未标定）
     */
    private int rrfK = 60;

    /**
     * 融合去重后、送进 rerank 前的候选上限（未标定）
     */
    private int maxCandidates = 30;

    /**
     * 同一来源（文档）最多占最终 topK 里的几个位置；&lt;=0 表示不限制（未标定）
     */
    private int maxPerSource = 2;

    /**
     * 向量召回的最小相似度。
     *
     * <p><b>默认 0.0（不过滤），这是刻意的，不要照抄 salary-leap 的 0.2。</b>
     * 0.2 是远端 text-embedding-v2 的分布；本地 bge-small-zh 连「今天天气怎么样」这种
     * 完全不相关的问题都能给到 0.676（M1 实测，见 knowledge/20），0.2 等于形同虚设。
     * 真正可用的阈值必须等评测集标定（未标定，待 knowledge/60）。
     */
    private double minScore = 0.0;
}
