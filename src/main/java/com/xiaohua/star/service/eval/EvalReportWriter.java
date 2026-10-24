package com.xiaohua.star.service.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.model.eval.EvalCaseScore;
import com.xiaohua.star.model.eval.EvalMetrics;
import com.xiaohua.star.model.eval.RagEvalReport;
import com.xiaohua.star.model.eval.VariantReport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 评估报告的落盘与呈现。
 *
 * <p>每次评估在 {@code report-dir} 下建一个时间戳目录，写 {@code report.json}
 * （机器可读，下次算差值）和 {@code report.md}（人读）。</p>
 *
 * <p><b>为什么一定要落盘</b>：评估的价值在「和上次比」。只返回一次结果的话，
 * 下次想比对就得手工抄数字 —— 那正是这套东西要取代的人工流程。</p>
 */
@Component
@Slf4j
public class EvalReportWriter {

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String REPORT_JSON = "report.json";
    private static final String REPORT_MD = "report.md";

    /** 自己造 ObjectMapper：Spring 那个会把 Long 序列化成字符串，报告文件里很难看 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Resource
    private RagEvalProperties properties;

    public String newRunId() {
        return LocalDateTime.now().format(RUN_ID_FORMAT);
    }

    /**
     * 比 {@code runId} 早、且用例集完全相同的最新一份报告。
     *
     * <p>不能只取「最近一份」：中间跑过一次单用例冒烟的话，拿它当基线算出来的不是进步，
     * 是两组样本的差异。所以按时间倒序找第一份 caseIds 相同的报告。</p>
     */
    public RagEvalReport loadPrevious(String runId, List<String> caseIds) {
        List<String> candidates = listRunIds().stream()
                .filter(id -> id.compareTo(runId) < 0)
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
        for (String id : candidates) {
            RagEvalReport report = read(id);
            if (report != null && java.util.Objects.equals(report.caseIds(), caseIds)) {
                return report;
            }
        }
        return null;
    }

    public RagEvalReport loadLatest() {
        Optional<String> latest = listRunIds().stream().max(String::compareTo);
        return latest.map(this::read).orElse(null);
    }

    private RagEvalReport read(String runId) {
        Path file = reportDir().resolve(runId).resolve(REPORT_JSON);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), RagEvalReport.class);
        } catch (IOException e) {
            log.warn("读取历史报告失败 [{}]: {}", file, e.getMessage());
            return null;
        }
    }

    /** 写出 report.json + report.md，返回报告目录。 */
    public Path write(RagEvalReport report) {
        Path dir = reportDir().resolve(report.runId());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(REPORT_JSON),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(REPORT_MD), renderMarkdown(report), StandardCharsets.UTF_8);
            log.info("评估报告已写入: {}", dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("写入评估报告失败 [{}]: {}", dir, e.getMessage(), e);
        }
        return dir.toAbsolutePath();
    }

    private List<String> listRunIds() {
        Path dir = reportDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("列出历史报告目录失败 [{}]: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private Path reportDir() {
        return Paths.get(properties.getReportDir());
    }

    /** 逐指标算与上次的差；只在同名变体之间比。 */
    static Map<String, Double> delta(EvalMetrics now, EvalMetrics before) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (now == null || before == null) {
            return result;
        }
        result.put("hitRate", now.hitRate() - before.hitRate());
        result.put("mrr", now.mrr() - before.mrr());
        result.put("recall", now.recall() - before.recall());
        result.put("precision", now.precision() - before.precision());
        result.put("diversity", now.diversity() - before.diversity());
        result.put("avgReturned", now.avgReturned() - before.avgReturned());
        result.put("emptyRate", now.emptyRate() - before.emptyRate());
        result.put("p50Millis", (double) (now.p50Millis() - before.p50Millis()));
        result.put("p95Millis", (double) (now.p95Millis() - before.p95Millis()));
        return result;
    }

    static String renderMarkdown(RagEvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 检索评估报告\n\n");

        sb.append("| 项目 | 值 |\n|------|-----|\n");
        sb.append("| 运行 ID | ").append(report.runId()).append(" |\n");
        sb.append("| 评估集 | ").append(report.evalSetFile())
                .append("（").append(report.caseCount()).append(" 条用例）|\n");
        sb.append("| 评测库 | ").append(report.kbId() == null ? "-" : report.kbId())
                .append(" / ").append(nullSafe(report.collectionName())).append(" |\n");
        sb.append("| 语料 | ").append(report.corpusDocs()).append(" 篇文档 / ")
                .append(report.corpusChunks()).append(" 个切片")
                .append(report.corpusComplete() ? "（完整）" : "（**不完整**）").append(" |\n");
        if (report.staleDocs() > 0) {
            sb.append("| 陈旧文档 | ").append(report.staleDocs())
                    .append(" 篇（不在当前语料目录，建议 `full=true` 重建）|\n");
        }
        sb.append("| rerank 模型 | ").append(report.rerankAvailable() ? "可用" : "不可用（no-rerank 与 all-on 相同）")
                .append(" |\n");
        sb.append("| 总耗时 | ").append(report.costMillis() / 1000.0).append(" 秒 |\n");
        sb.append("| 对比基线 | ")
                .append(report.previousRunId() == null
                        ? "无（首次运行，或与上一份的用例集不同）"
                        : report.previousRunId())
                .append(" |\n\n");

        if (report.collectionChanged()) {
            sb.append("> ⚠️ **评估期间索引集合发生了变化**，前后两段用的不是同一个集合，这批指标前后不可比。\n\n");
        }
        if (!report.corpusComplete()) {
            sb.append("> ⚠️ **语料不完整**：有文档没有索引成功。先看日志/error，再重跑；")
                    .append("不完整语料上的命中率会低估真实水平。\n\n");
        }
        double worstEmpty = report.variants().stream()
                .mapToDouble(v -> v.metrics().emptyRate()).max().orElse(0);
        if (worstEmpty > 0.2) {
            sb.append("> ⚠️ 空结果率高达 ").append(pct(worstEmpty))
                    .append(" —— 先确认 Milvus 与本地 embedding 是否正常，")
                    .append("链路整体失灵时命中率没有分析价值。\n\n");
        }
        sb.append("> 提醒：这是**回归防护网**，不是检索质量的绝对评分。")
                .append("它回答「改动有没有让原本对的变错」，不代表线上用户的真实满意度。\n\n");

        sb.append("## 一、变体对比\n\n");
        sb.append("| 变体 | 用例 | 失败 | Hit@K | MRR@K | Recall@K | Precision@K | Diversity@K | 平均返回 | 空结果率 | P50 | P95 |\n");
        sb.append("|------|------|------|-------|-------|----------|-------------|-------------|----------|----------|-----|-----|\n");
        for (VariantReport variant : report.variants()) {
            EvalMetrics m = variant.metrics();
            sb.append("| ").append(variant.label())
                    .append(" | ").append(m.total())
                    .append(" | ").append(m.failed())
                    .append(" | ").append(pct(m.hitRate()))
                    .append(" | ").append(dec(m.mrr()))
                    .append(" | ").append(pct(m.recall()))
                    .append(" | ").append(pct(m.precision()))
                    .append(" | ").append(dec(m.diversity()))
                    .append(" | ").append(dec(m.avgReturned()))
                    .append(" | ").append(pct(m.emptyRate()))
                    .append(" | ").append(m.p50Millis()).append("ms")
                    .append(" | ").append(m.p95Millis()).append("ms")
                    .append(" |\n");
        }
        sb.append("\n> Hit@K / MRR@K 里的 K 是**该变体自己的 topK**（all-on=5，topk-10=10），")
                .append("所以不同 K 的命中率不能直接横向比较。\n\n");

        sb.append("### 这组参数具体是什么\n\n");
        for (VariantReport variant : report.variants()) {
            sb.append("- **").append(variant.label()).append("**：").append(variant.options()).append('\n');
        }
        sb.append('\n');

        boolean anyDelta = report.variants().stream()
                .anyMatch(v -> v.deltaVsPrevious() != null && !v.deltaVsPrevious().isEmpty());
        sb.append("### 与上次的差值\n\n");
        if (!anyDelta) {
            sb.append("没有可比的上一份报告（首次运行，或用例集不同）。\n\n");
        } else {
            sb.append("| 变体 | ΔHit@K | ΔMRR@K | ΔRecall@K | ΔPrecision@K | ΔDiversity@K | Δ平均返回 | Δ空结果率 | ΔP50 |\n");
            sb.append("|------|--------|--------|-----------|--------------|--------------|-----------|-----------|------|\n");
            for (VariantReport variant : report.variants()) {
                Map<String, Double> d = variant.deltaVsPrevious();
                if (d == null || d.isEmpty()) {
                    sb.append("| ").append(variant.label()).append(" | 无上次数据 | | | | | | | |\n");
                    continue;
                }
                sb.append("| ").append(variant.label())
                        .append(" | ").append(deltaCell(d, "hitRate", true))
                        .append(" | ").append(deltaCell(d, "mrr", false))
                        .append(" | ").append(deltaCell(d, "recall", true))
                        .append(" | ").append(deltaCell(d, "precision", true))
                        .append(" | ").append(deltaCell(d, "diversity", false))
                        .append(" | ").append(deltaCell(d, "avgReturned", false))
                        .append(" | ").append(deltaCell(d, "emptyRate", true))
                        .append(" | ").append(deltaCellMillis(d, "p50Millis"))
                        .append(" |\n");
            }
            sb.append("\n> **Δ = 0 也是一条结论**：说明这次改动只影响耗时、没影响命中。\n\n");
        }

        sb.append("## 二、未命中明细\n\n");
        for (VariantReport variant : report.variants()) {
            List<EvalCaseScore> missedCases = variant.cases().stream()
                    .filter(score -> !score.hit() || score.error() != null)
                    .toList();
            sb.append("### ").append(variant.label())
                    .append("（未命中 ").append(missedCases.size()).append(" / ")
                    .append(variant.cases().size()).append("）\n\n");
            if (missedCases.isEmpty()) {
                sb.append("全部命中。\n\n");
                continue;
            }
            sb.append("| 用例 | 查询 | 期望来源 | 原因 |\n|------|------|----------|------|\n");
            for (EvalCaseScore score : missedCases) {
                sb.append("| ").append(score.caseId())
                        .append(" | ").append(truncate(score.query(), 40))
                        .append(" | ").append(String.join("、", score.expectedSources()))
                        .append(" | ").append(reason(score))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 三、原因分类说明\n\n");
        sb.append("- **不在库**：期望的文章根本不在索引里 → **数据**的锅，只能补数据源。\n");
        sb.append("- **检索漏掉**：文章在索引里但没进 topK → **检索**的锅，才轮到调参/改链路。\n");
        sb.append("- **调用失败**：那一趟检索直接报错了，看日志定位。\n\n");
        sb.append("这条分类是这份报告最该先看的一列：它把「该补数据还是该调检索」变成一眼可见的事实。\n");
        return sb.toString();
    }

    private static String reason(EvalCaseScore score) {
        if (score.error() != null) {
            return "调用失败：" + truncate(score.error(), 60);
        }
        List<String> parts = new ArrayList<>();
        if (!score.notInIndex().isEmpty()) {
            parts.add("不在库（" + String.join("、", shortNames(score.notInIndex())) + "）");
        }
        if (!score.missed().isEmpty()) {
            parts.add("检索漏掉（" + String.join("、", shortNames(score.missed())) + "）");
        }
        return parts.isEmpty() ? "未命中" : String.join("；", parts);
    }

    private static List<String> shortNames(List<String> sources) {
        List<String> names = new ArrayList<>(sources.size());
        for (String source : sources) {
            int slash = source.lastIndexOf('/');
            names.add(slash < 0 ? source : source.substring(slash + 1));
        }
        return names;
    }

    private static String deltaCell(Map<String, Double> deltas, String key, boolean percent) {
        Double value = deltas.get(key);
        if (value == null) {
            return "-";
        }
        if (value == 0) {
            return "0";
        }
        String text = percent ? pct(Math.abs(value)) : dec(Math.abs(value));
        return (value > 0 ? "+" : "−") + text;
    }

    private static String deltaCellMillis(Map<String, Double> deltas, String key) {
        Double value = deltas.get(key);
        if (value == null) {
            return "-";
        }
        if (value == 0) {
            return "0";
        }
        return (value > 0 ? "+" : "−") + Math.round(Math.abs(value)) + "ms";
    }

    private static String pct(double value) {
        return String.format("%.1f%%", value * 100);
    }

    private static String dec(double value) {
        return String.format("%.3f", value);
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('|', '/').trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static String nullSafe(String text) {
        return text == null ? "-" : text;
    }
}
