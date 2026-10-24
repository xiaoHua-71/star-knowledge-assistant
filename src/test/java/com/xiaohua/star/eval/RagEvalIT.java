package com.xiaohua.star.eval;

import com.xiaohua.star.model.eval.EvalCaseScore;
import com.xiaohua.star.model.eval.RagEvalReport;
import com.xiaohua.star.model.eval.VariantReport;
import com.xiaohua.star.model.vo.RagCorpusSummaryVO;
import com.xiaohua.star.service.eval.EvalCorpusService;
import com.xiaohua.star.service.eval.RagEvalProperties;
import com.xiaohua.star.service.eval.RagEvalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线评测集成测试（opt-in）。
 *
 * <p>类名以 {@code IT} 结尾，Surefire 默认不包含，普通 {@code mvn test} 不会执行。
 * 需要 MySQL、Redis、Milvus 都可达；首次运行会导入 94 篇语料（本地 ONNX，本机实测约 1–2 分钟）：</p>
 *
 * <pre>
 * export JAVA_HOME=/e/Java/jdk-21.0.5
 * mvn test -Dtest=RagEvalIT -DfailIfNoSpecifiedTests=false
 * # 只跑 part：
 * mvn test -Dtest=RagEvalIT -Drag.eval.it.variants=all-on \
 *     -Drag.eval.it.caseIds=java-proxy-01,jvm-memory-01 -DfailIfNoSpecifiedTests=false
 * # 整库重建语料：
 * mvn test -Dtest=RagEvalIT -Drag.eval.corpus.full=true -DfailIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
class RagEvalIT {

    @Autowired
    private RagEvalService ragEvalService;

    @Autowired
    private EvalCorpusService corpusService;

    @Autowired
    private RagEvalProperties properties;

    @Test
    void 跑完整评估并产出报告() throws Exception {
        boolean full = Boolean.parseBoolean(System.getProperty("rag.eval.corpus.full", "false"));
        RagCorpusSummaryVO corpus = corpusService.ensureCorpus(full);
        assertThat(corpus.corpusFiles()).isEqualTo(94);
        assertThat(corpus.complete()).as("语料失败明细: %s", corpus.failures()).isTrue();
        assertThat(corpus.failures()).isEmpty();

        RagEvalReport report = ragEvalService.runNow(
                split(System.getProperty("rag.eval.it.variants")),
                split(System.getProperty("rag.eval.it.caseIds")));

        assertThat(report).isNotNull();
        assertThat(report.variants()).isNotEmpty();
        assertThat(report.corpusDocs()).isEqualTo(94);
        assertThat(report.corpusComplete()).isTrue();
        assertThat(report.caseCount()).isPositive();

        for (VariantReport variant : report.variants()) {
            assertThat(variant.cases()).hasSize(report.caseCount());
            assertThat(variant.metrics().total()).isEqualTo(report.caseCount());
            assertThat(variant.metrics().hitRate()).isBetween(0.0, 1.0);
            assertThat(variant.metrics().mrr()).isBetween(0.0, 1.0);
            assertThat(variant.metrics().recall()).isBetween(0.0, 1.0);
            assertThat(variant.cases()).allMatch(score -> score.error() == null,
                    "有用例检索失败，报告会低估真实水平");
        }

        Path reportMarkdown = Paths.get(properties.getReportDir(), report.runId(), "report.md");
        Path reportJson = Paths.get(properties.getReportDir(), report.runId(), "report.json");
        assertThat(Files.exists(reportMarkdown)).isTrue();
        assertThat(Files.exists(reportJson)).isTrue();
    }

    @Test
    void 缺失的期望来源被归类为不在库() {
        RagEvalReport report = ragEvalService.runNow(List.of("all-on"), List.of("testing-interview-01"));

        assertThat(report.caseCount()).isEqualTo(1);
        EvalCaseScore score = report.variants().get(0).cases().get(0);
        assertThat(score.expectedSources()).contains("knowledge/testing.txt");
        assertThat(score.notInIndex()).contains("knowledge/testing.txt");
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
