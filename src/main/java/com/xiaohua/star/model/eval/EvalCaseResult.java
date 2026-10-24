package com.xiaohua.star.model.eval;

import java.util.List;

/**
 * 一条用例跑完一次的原始观测结果（还没算分）。
 *
 * <p>只装「事实」：期望什么、实际返回了哪些来源、花了多久、有没有报错。
 * 所有派生量都由 {@code RetrievalMetrics} 算 —— 这样指标逻辑是纯函数，可以完全离线单测。</p>
 *
 * @param caseId          用例标识
 * @param query           查询文本
 * @param expectedSources 期望命中的来源
 * @param returnedSources 实际返回片段的来源，按相关性排序，每个片段一条
 * @param tags            方向标签
 * @param costMillis      本条耗时（毫秒）
 * @param error           失败原因；正常为 null
 */
public record EvalCaseResult(String caseId,
                             String query,
                             List<String> expectedSources,
                             List<String> returnedSources,
                             List<String> tags,
                             long costMillis,
                             String error) {

    /** 检索成功但一条都没返回 */
    public boolean isEmpty() {
        return error == null && (returnedSources == null || returnedSources.isEmpty());
    }
}
