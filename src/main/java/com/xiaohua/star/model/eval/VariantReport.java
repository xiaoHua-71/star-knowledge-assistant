package com.xiaohua.star.model.eval;

import java.util.List;
import java.util.Map;

/**
 * 一个变体跑完的结果：整体指标 + 逐条明细 + 与上次的差值。
 *
 * @param variantId       变体标识
 * @param label           变体中文名
 * @param options         这组用的是哪套参数（{@code SearchOptions.describe()}）
 * @param metrics         整体指标
 * @param cases           逐条明细
 * @param deltaVsPrevious 各指标与上次同名变体的差；没有可比的上一份时为空
 */
public record VariantReport(String variantId,
                            String label,
                            String options,
                            EvalMetrics metrics,
                            List<EvalCaseScore> cases,
                            Map<String, Double> deltaVsPrevious) {
}
