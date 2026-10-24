package com.xiaohua.star.service.eval;

import com.xiaohua.star.model.eval.EvalCaseResult;
import com.xiaohua.star.model.eval.EvalCaseScore;
import com.xiaohua.star.model.eval.EvalMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标计算单测：纯函数，<b>不启 Spring、不联网</b>。
 *
 * <p>指标算错的后果是「看起来很专业的报告，结论全是错的」，而且没人会怀疑一张表格，
 * 所以这里算得细一点值得。重点守三件事：名次正确、未命中原因分类正确、不出现 NaN。</p>
 */
class RetrievalMetricsTest {

    private static final String JAVA_PROXY = "https://javaguide.cn/java/basis/proxy.html";
    private static final String JVM_GC = "https://javaguide.cn/java/jvm/jvm-garbage-collection.html";

    private static EvalCaseScore score(List<String> expected, List<String> returned, Set<String> indexSources) {
        return RetrievalMetrics.score(
                new EvalCaseResult("case", "查询", expected, returned, List.of(), 100, null), indexSources);
    }

    private static EvalCaseScore scoreWithError(List<String> expected) {
        return RetrievalMetrics.score(
                new EvalCaseResult("case", "查询", expected, List.of(), List.of(), 100, "向量召回失败"),
                Set.of(JAVA_PROXY));
    }

    @Test
    void 期望来源写在第一个位置时名次为一() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY, JVM_GC), Set.of(JAVA_PROXY));

        assertThat(result.hit()).isTrue();
        assertThat(result.hitRank()).isEqualTo(1);
        assertThat(result.hitCount()).isEqualTo(1);
    }

    @Test
    void 命中排在第二位时名次为二() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html"), List.of(JVM_GC, JAVA_PROXY), Set.of(JAVA_PROXY));

        assertThat(result.hitRank()).isEqualTo(2);
    }

    @Test
    void 多条期望来源只被命中一个时名次取命中的那个() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html", "java/jvm/jvm-garbage-collection.html"),
                List.of(JVM_GC), Set.of(JAVA_PROXY, JVM_GC));

        assertThat(result.hitRank()).isEqualTo(1);
        assertThat(result.hitExpected()).containsExactly("java/jvm/jvm-garbage-collection.html");
        assertThat(result.missed()).containsExactly("java/basis/proxy.html");
    }

    @Test
    void 同一篇占两个位置时命中条数为二但名次仍是一() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY, JAVA_PROXY), Set.of(JAVA_PROXY));

        assertThat(result.hitRank()).isEqualTo(1);
        assertThat(result.hitCount()).isEqualTo(2);
        assertThat(result.distinctSources()).isEqualTo(1);
    }

    @Test
    void 期望来源不在索引里时归为数据缺失() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html"), List.of(JVM_GC), Set.of(JVM_GC));

        assertThat(result.hit()).isFalse();
        assertThat(result.notInIndex()).containsExactly("java/basis/proxy.html");
        assertThat(result.missed()).isEmpty();
    }

    @Test
    void 期望来源在索引里但没捞到时归为检索漏掉() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html"), List.of(JVM_GC), Set.of(JVM_GC, JAVA_PROXY));

        assertThat(result.notInIndex()).isEmpty();
        assertThat(result.missed()).containsExactly("java/basis/proxy.html");
    }

    @Test
    void 检索失败时记为错误且不计入命中() {
        EvalCaseScore result = scoreWithError(List.of("java/basis/proxy.html"));

        assertThat(result.error()).isEqualTo("向量召回失败");
        assertThat(result.hit()).isFalse();
        assertThat(result.returnedCount()).isZero();
    }

    @Test
    void 无来源元数据的片段不参与命中但计入返回数() {
        EvalCaseScore result = score(List.of("java/basis/proxy.html"),
                java.util.Arrays.asList(null, JAVA_PROXY), Set.of(JAVA_PROXY));

        assertThat(result.returnedCount()).isEqualTo(2);
        assertThat(result.hitRank()).isEqualTo(2);
        assertThat(result.distinctSources()).isEqualTo(1);
    }

    @Test
    void 全部命中时整体命中率为一且倒数排名为一() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY), Set.of(JAVA_PROXY)),
                score(List.of("java/jvm/jvm-garbage-collection.html"), List.of(JVM_GC), Set.of(JVM_GC))));

        assertThat(metrics.total()).isEqualTo(2);
        assertThat(metrics.hitRate()).isEqualTo(1.0);
        assertThat(metrics.mrr()).isEqualTo(1.0);
    }

    @Test
    void 名次为二时倒数排名贡献半数() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                score(List.of("java/basis/proxy.html"), List.of(JVM_GC, JAVA_PROXY), Set.of(JAVA_PROXY))));

        assertThat(metrics.mrr()).isEqualTo(0.5);
    }

    @Test
    void 命中一半时命中率为0点5() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY), Set.of(JAVA_PROXY)),
                score(List.of("java/jvm/jvm-garbage-collection.html"), List.of(JAVA_PROXY), Set.of(JVM_GC))));

        assertThat(metrics.hitRate()).isEqualTo(0.5);
        assertThat(metrics.mrr()).isEqualTo(0.5);
    }

    @Test
    void 来源全不相同时多样度为一() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY, JVM_GC), Set.of(JAVA_PROXY, JVM_GC))));

        assertThat(metrics.diversity()).isEqualTo(1.0);
    }

    @Test
    void 来源全相同时多样度等于片段数的倒数() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY, JAVA_PROXY), Set.of(JAVA_PROXY))));

        assertThat(metrics.diversity()).isEqualTo(0.5);
        // 多样度可以被「少返回」刷高，所以必须和平均返回数一起看
        assertThat(metrics.avgReturned()).isEqualTo(2.0);
    }

    @Test
    void 返回为空时空结果率为一且准确率为零() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                score(List.of("java/basis/proxy.html"), List.of(), Set.of(JAVA_PROXY))));

        assertThat(metrics.emptyRate()).isEqualTo(1.0);
        assertThat(metrics.precision()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.avgReturned()).isZero();
    }

    @Test
    void 出错用例计入失败数并按未命中处理() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of(
                scoreWithError(List.of("java/basis/proxy.html")),
                score(List.of("java/basis/proxy.html"), List.of(JAVA_PROXY), Set.of(JAVA_PROXY))));

        assertThat(metrics.failed()).isEqualTo(1);
        assertThat(metrics.hitRate()).isEqualTo(0.5);
        // 出错的用例不算「空结果」——空结果率是用来发现「链路能跑通但一条都召回不到」的
        assertThat(metrics.emptyRate()).isZero();
    }

    @Test
    void 没有任何用例时所有指标为零而不是NaN() {
        EvalMetrics metrics = RetrievalMetrics.aggregate(List.of());

        assertThat(metrics.total()).isZero();
        assertThat(metrics.hitRate()).isZero();
        assertThat(metrics.mrr()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.precision()).isZero();
        assertThat(metrics.diversity()).isZero();
        assertThat(metrics.emptyRate()).isZero();
        assertThat(metrics.p50Millis()).isZero();
    }

    @Test
    void 百分位取最近秩() {
        List<Long> values = List.of(400L, 100L, 300L, 200L);

        assertThat(RetrievalMetrics.percentile(values, 50)).isEqualTo(200);
        assertThat(RetrievalMetrics.percentile(values, 95)).isEqualTo(400);
        assertThat(RetrievalMetrics.percentile(values, 25)).isEqualTo(100);
    }

    @Test
    void 百分位在单样本与空样本下安全返回() {
        assertThat(RetrievalMetrics.percentile(List.of(77L), 95)).isEqualTo(77);
        assertThat(RetrievalMetrics.percentile(List.of(), 50)).isZero();
        assertThat(RetrievalMetrics.percentile(null, 50)).isZero();
    }
}
