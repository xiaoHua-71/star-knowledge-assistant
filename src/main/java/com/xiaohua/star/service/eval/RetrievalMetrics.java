package com.xiaohua.star.service.eval;

import com.xiaohua.star.model.eval.EvalCaseResult;
import com.xiaohua.star.model.eval.EvalCaseScore;
import com.xiaohua.star.model.eval.EvalMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 检索指标计算：把「一条条观测结果」变成「一组数字」。
 *
 * <p><b>整类都是纯函数</b>：不碰网络、不碰 Spring、不碰文件系统。指标算错的后果是
 * 「看起来很专业的报告，结论全是错的」，而且没人会怀疑一张表格。</p>
 *
 * <p>分母统一保护：任何一项算不出（分母为 0）都返回 0 而不是 NaN。</p>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * 给一条观测结果算分，并区分「不在索引里」和「检索漏掉」。
     *
     * @param result       一条用例的原始观测
     * @param indexSources 当前索引里的全部来源；传空表示「不知道索引里有什么」，
     *                     此时未命中的一律归为检索的锅
     */
    public static EvalCaseScore score(EvalCaseResult result, Set<String> indexSources) {
        List<String> returned = result.returnedSources() == null ? List.of() : result.returnedSources();
        List<String> expected = result.expectedSources() == null ? List.of() : result.expectedSources();
        boolean indexKnown = indexSources != null && !indexSources.isEmpty();

        List<String> hitExpected = new ArrayList<>();
        int hitRank = 0;
        int hitCount = 0;
        for (int i = 0; i < returned.size(); i++) {
            String actual = returned.get(i);
            if (actual == null) {
                continue;
            }
            for (String exp : expected) {
                if (EvalSourceMatcher.matches(exp, actual)) {
                    hitCount++;
                    if (hitRank == 0) {
                        hitRank = i + 1;
                    }
                    if (!hitExpected.contains(exp)) {
                        hitExpected.add(exp);
                    }
                    break;
                }
            }
        }

        List<String> notInIndex = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (String exp : expected) {
            if (hitExpected.contains(exp)) {
                continue;
            }
            if (indexKnown && !inIndex(exp, indexSources)) {
                notInIndex.add(exp);
            } else {
                missed.add(exp);
            }
        }

        int distinctSources = (int) returned.stream()
                .filter(Objects::nonNull)
                .map(EvalSourceMatcher::normalize)
                .filter(source -> !source.isEmpty())
                .distinct()
                .count();

        return new EvalCaseScore(result.caseId(), result.query(), List.copyOf(expected),
                // 不能用 List.copyOf：没有来源元数据的片段会给出一条 null
                Collections.unmodifiableList(new ArrayList<>(returned)),
                List.copyOf(hitExpected), List.copyOf(notInIndex),
                List.copyOf(missed), hitRank, hitCount, returned.size(), distinctSources,
                result.costMillis(), result.error());
    }

    private static boolean inIndex(String expected, Set<String> indexSources) {
        return indexSources.stream().anyMatch(actual -> EvalSourceMatcher.matches(expected, actual));
    }

    /**
     * 汇总一组用例的指标。召回率/准确率用<b>微平均</b>（先累加再相除），
     * 避免「期望来源多的用例」和「只有一条期望的用例」权重相同。
     */
    public static EvalMetrics aggregate(List<EvalCaseScore> scores) {
        int total = scores.size();
        if (total == 0) {
            return new EvalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int failed = 0;
        int hits = 0;
        int empty = 0;
        double mrrSum = 0;
        double diversitySum = 0;
        int expectedTotal = 0;
        int expectedHit = 0;
        int returnedTotal = 0;
        int hitTotal = 0;
        List<Long> costs = new ArrayList<>(total);

        for (EvalCaseScore score : scores) {
            if (score.error() != null) {
                failed++;
            }
            if (score.hit()) {
                hits++;
                mrrSum += 1.0 / score.hitRank();
            }
            if (score.error() == null && score.returnedCount() == 0) {
                empty++;
            }
            if (score.returnedCount() > 0) {
                diversitySum += (double) score.distinctSources() / score.returnedCount();
            }
            expectedTotal += score.expectedSources().size();
            expectedHit += score.hitExpected().size();
            returnedTotal += score.returnedCount();
            hitTotal += score.hitCount();
            costs.add(score.costMillis());
        }

        return new EvalMetrics(total, failed,
                div(hits, total),
                div(mrrSum, total),
                div(expectedHit, expectedTotal),
                div(hitTotal, returnedTotal),
                div(diversitySum, total),
                div(returnedTotal, total),
                div(empty, total),
                percentile(costs, 50),
                percentile(costs, 95));
    }

    private static double div(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    /**
     * 最近秩百分位：排序后取第 ceil(p/100 * n) 个。样本少时比插值法更保守。
     */
    static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        int index = (int) Math.ceil(percentile / 100.0 * n) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= n) {
            index = n - 1;
        }
        return sorted.get(index);
    }
}
