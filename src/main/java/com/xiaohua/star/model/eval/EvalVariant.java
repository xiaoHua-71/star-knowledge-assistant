package com.xiaohua.star.model.eval;

import com.xiaohua.star.ai.SearchOptions;

import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * 检索变体：在同一套生产配置上关掉/改动某个环节，用来量出这个环节贡献了多少。
 *
 * <p>变体就是「改哪几个参数」的表达式，用 {@code UnaryOperator<SearchOptions>}
 * 表达一目了然、类型安全。跑哪几组由配置 {@code rag.eval.default-variants} 决定。</p>
 *
 * <p>本项目没有查询改写，所以不移植 salary-leap 的 {@code no-rewrite}；
 * rerank 默认关闭时 {@code no-rerank} 与 {@code all-on} 相同，报告会标注模型是否可用。</p>
 */
public enum EvalVariant {

    /** 全开，等于线上真实配置 —— 报告第一行就是当前生产水平 */
    ALL_ON("all-on", "全开（生产基线）", base -> base),

    /** 只剩向量一路，量化 BM25 的贡献 */
    NO_HYBRID("no-hybrid", "关混合检索（只剩向量一路）", base -> base.withHybrid(false)),

    /** 关掉来源多样性限制，看一篇文章占满 topK 时命中率是涨还是跌 */
    NO_DIVERSITY("no-diversity", "全开但不限制同一来源", base -> base.withMaxPerSource(0)),

    /** 关掉精排。模型不可用时与 all-on 相同（报告会标注） */
    NO_RERANK("no-rerank", "关 rerank 精排", base -> base.withRerank(false)),

    /** 多给五条，看正确答案有多少排在 6~10 位 —— 也就是「召回的天花板」 */
    TOPK_10("topk-10", "全开但取前 10 条", base -> base.withTopK(10)),

    /*
     * 以下为参数标定/回归变体（标定记录见 knowledge/60 第十一节），默认不跑。
     * minScore 只作用于向量那一路（BM25 不受影响），分值是余弦相似度而非 RRF 融合分。
     * 标定过程中试过的 0.4~0.95、recall 5~50 只保留有诊断价值的几档，避免枚举无限膨胀。
     */

    /** 关掉最低分过滤，量化当前 0.8 阈值带来的收益 */
    NO_MIN_SCORE("min-score-0", "关掉向量最低分过滤", base -> base.withMinScore(0.0)),

    /** 更激进的阈值：命中率不降但会换掉两条用例，作为「不要调过头」的警戒线 */
    MIN_SCORE_090("min-score-090", "向量召回最低分 0.9（激进）", base -> base.withMinScore(0.9)),

    /** 比默认 15 更小的召回数，量化「少给候选」的代价 */
    RECALL_10("recall-10", "双路各召回 10 条", base -> base.withRecall(10)),

    /** 比默认 15 更大的召回数，量化「多给候选」是帮忙还是帮倒忙 */
    RECALL_30("recall-30", "双路各召回 30 条", base -> base.withRecall(30));

    private final String id;
    private final String label;
    private final UnaryOperator<SearchOptions> override;

    EvalVariant(String id, String label, UnaryOperator<SearchOptions> override) {
        this.id = id;
        this.label = label;
        this.override = override;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** 在生产配置的基础上应用本变体的开关。 */
    public SearchOptions apply(SearchOptions base) {
        return override.apply(base);
    }

    /** 按 id 找变体，id 对不上时抛出可用列表。 */
    public static EvalVariant byId(String id) {
        return Arrays.stream(values())
                .filter(variant -> variant.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知的评估变体 [" + id + "]，可用：" + allIds()));
    }

    /** 解析一组 id；空列表时回退到「只跑全开基线」。 */
    public static List<EvalVariant> parseAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of(ALL_ON);
        }
        return ids.stream().map(EvalVariant::byId).collect(Collectors.toList());
    }

    public static String allIds() {
        return Arrays.stream(values()).map(EvalVariant::getId).collect(Collectors.joining(", "));
    }
}
