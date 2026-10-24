package com.xiaohua.star.service.eval;

import com.xiaohua.star.model.eval.EvalCaseScore;
import com.xiaohua.star.model.eval.EvalMetrics;
import com.xiaohua.star.model.eval.RagEvalReport;
import com.xiaohua.star.model.eval.VariantReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告渲染与差值单测：纯函数，<b>不写文件、不启 Spring</b>。
 *
 * <p>报告是这套东西唯一的产出物。渲染崩了等于整次评估白跑，所以哪怕只是拼字符串也测一下。</p>
 */
class EvalReportWriterTest {

    private static EvalMetrics metrics(double hitRate) {
        return new EvalMetrics(30, 0, hitRate, 0.8, 0.7, 0.6, 0.9, 3.0, 0.0, 120, 400);
    }

    private static EvalCaseScore miss(String caseId, String query,
                                      List<String> notInIndex, List<String> missed) {
        return new EvalCaseScore(caseId, query, List.of("a/b.html"), List.of("x/y.html"),
                List.of(), notInIndex, missed, 0, 0, 1, 1, 100, null);
    }

    private static RagEvalReport report(String previousRunId, List<VariantReport> variants) {
        return new RagEvalReport("20260919-220000-000", 0, 60_000,
                1L, "star_kb_1", false, "docs/rag-eval/eval-set.json",
                94, 1200, true, 0, false, 30, List.of("c1", "c2"), previousRunId, variants);
    }

    @Test
    void 首次运行时说明没有对比基线() {
        String markdown = EvalReportWriter.renderMarkdown(report(null, List.of(
                new VariantReport("all-on", "全开（生产基线）", "topK=5", metrics(0.8),
                        List.of(), Map.of()))));

        assertThat(markdown).contains("无（首次运行，或与上一份的用例集不同）");
        assertThat(markdown).contains("没有可比的上一份报告");
    }

    @Test
    void 报告写出变体对比表与这组参数() {
        String markdown = EvalReportWriter.renderMarkdown(report("20260919-100000-000", List.of(
                new VariantReport("all-on", "全开（生产基线）", "hybrid=true, topK=5", metrics(0.8),
                        List.of(), Map.of("hitRate", 0.1)))));

        assertThat(markdown).contains("| 变体 | 用例 | 失败 |");
        assertThat(markdown).contains("全开（生产基线）");
        assertThat(markdown).contains("hybrid=true, topK=5");
        assertThat(markdown).contains("80.0%");
        assertThat(markdown).contains("+10.0%");
    }

    @Test
    void 差值为零时也显式标注而不是留空() {
        String markdown = EvalReportWriter.renderMarkdown(report("20260919-100000-000", List.of(
                new VariantReport("no-rerank", "关 rerank 精排", "rerank=false",
                        metrics(0.8), List.of(), Map.of("hitRate", 0.0)))));

        assertThat(markdown).contains("Δ = 0 也是一条结论");
        assertThat(markdown).contains("| 0 |");
    }

    @Test
    void 未命中明细区分不在库与检索漏掉() {
        String markdown = EvalReportWriter.renderMarkdown(report(null, List.of(
                new VariantReport("all-on", "全开（生产基线）", "topK=5", metrics(0.5),
                        List.of(miss("case-not-in-index", "查不到的问题", List.of("a/missing.html"), List.of()),
                                miss("case-missed", "捞不到的问题", List.of(), List.of("a/exists.html"))),
                        Map.of()))));

        assertThat(markdown).contains("不在库（missing.html）");
        assertThat(markdown).contains("检索漏掉（exists.html）");
    }

    @Test
    void 空结果率过高时先给链路报警() {
        EvalMetrics bad = new EvalMetrics(30, 0, 0.0, 0, 0, 0, 0, 0, 0.5, 100, 200);
        String markdown = EvalReportWriter.renderMarkdown(report(null, List.of(
                new VariantReport("all-on", "全开（生产基线）", "topK=5", bad, List.of(), Map.of()))));

        assertThat(markdown).contains("空结果率高达 50.0%");
        assertThat(markdown).contains("链路整体失灵");
    }

    @Test
    void 评估期间索引被改时标注结果不可比() {
        RagEvalReport changed = new RagEvalReport("20260919-220000-000", 0, 1000,
                1L, "star_kb_1", true, "docs/rag-eval/eval-set.json",
                94, 1200, true, 0, false, 30, List.of("c1"), null, List.of());

        assertThat(EvalReportWriter.renderMarkdown(changed))
                .contains("评估期间索引集合发生了变化")
                .contains("前后不可比");
    }

    @Test
    void 语料不完整时报警() {
        RagEvalReport incomplete = new RagEvalReport("20260919-220000-000", 0, 1000,
                1L, "star_kb_1", false, "docs/rag-eval/eval-set.json",
                93, 1100, false, 0, false, 30, List.of("c1"), null, List.of());

        assertThat(EvalReportWriter.renderMarkdown(incomplete)).contains("语料不完整");
    }

    @Test
    void 差值按指标逐项相减() {
        Map<String, Double> delta = EvalReportWriter.delta(metrics(0.8), metrics(0.5));

        assertThat(delta.get("hitRate")).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(delta.get("mrr")).isZero();
    }

    @Test
    void 没有可比的上一份时差值为空() {
        assertThat(EvalReportWriter.delta(metrics(0.8), null)).isEmpty();
        assertThat(EvalReportWriter.delta(null, metrics(0.8))).isEmpty();
    }
}
