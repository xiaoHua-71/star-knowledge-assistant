package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索链路参数（application.yml 的 knowledge.search.*）
 *
 * <p>起点从 salary-leap 移植，但语料不同。{@code vectorRecall / bm25Recall / minScore}
 * 已于 2026-10-27 用 30 条评估集标定（记录见 knowledge/60 第十一节）；
 * {@code rrfK / maxCandidates / maxPerSource} 仍是经验值，未标定。
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
     * 向量这一路召回多少候选（已标定：20 → 15）
     */
    private int vectorRecall = 15;

    /**
     * BM25 这一路召回多少候选（已标定：20 → 15）
     */
    private int bm25Recall = 15;

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
     * 向量召回的最小相似度（只作用于向量这一路，BM25 不受影响）。
     *
     * <p><b>0.8 是 2026-10-27 用 30 条评估集标定的，不能跨 embedding 模型搬运。</b>
     * 本地 bge-small-zh 的分数被压在 0.67~0.9 的窄带里：0.4~0.75 完全不过滤任何结果，
     * 0.85 起开始有期望来源被误杀。0.8 是「确实过滤了弱相关、但没丢掉任何命中」的最后一档。
     * 换模型/换语料后必须重跑评测（见 knowledge/60 第十一节）。
     */
    private double minScore = 0.8;
}
