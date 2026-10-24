package com.xiaohua.star.model.eval;

/**
 * 一组用例的整体指标（一个变体一份）。
 *
 * <p>两条容易踩的：</p>
 * <ul>
 *   <li>{@link #diversity()} <b>不能单独看</b>：少返回几条就能把多样度刷上去，
 *       必须和 {@link #avgReturned()} 同屏对比。</li>
 *   <li>{@link #emptyRate()} 高说明链路整体失灵，这时命中率没有分析价值。</li>
 * </ul>
 *
 * @param total       参与统计的用例数
 * @param failed      抛出异常的用例数（这些用例按未命中计入命中率）
 * @param hitRate     命中率：至少命中一个期望来源的用例占比
 * @param mrr         平均倒数排名：命中排得越靠前越高，未命中记 0
 * @param recall      召回率：所有期望来源里被覆盖的比例
 * @param precision   准确率：返回的片段里命中期望来源的比例（切片级）
 * @param diversity   来源多样度：不同来源数 / 返回片段数（切片级）
 * @param avgReturned 平均返回片段数
 * @param emptyRate   空结果率
 * @param p50Millis   耗时中位数
 * @param p95Millis   耗时 P95
 */
public record EvalMetrics(int total,
                          int failed,
                          double hitRate,
                          double mrr,
                          double recall,
                          double precision,
                          double diversity,
                          double avgReturned,
                          double emptyRate,
                          long p50Millis,
                          long p95Millis) {
}
