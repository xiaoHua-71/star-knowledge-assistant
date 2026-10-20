package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 调用汇总（{@code GET /tools/logs/summary}，M12）。
 *
 * <p><b>计数字段是 Long，会被全局 JsonConfig 序列化成字符串</b>（与分页响应一致）；
 * {@code avgElapsedMs} / {@code avgFirstTokenMs} 是数字。
 * {@code costYuan} 是字符串（金额不能走浮点），价格未配置时为 {@code null}。
 */
@Data
public class CallSummaryVO implements Serializable {

    /** 本次汇总的 run（未按 run 过滤时为 null） */
    private Long runId;

    private Long callCount;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    /** 成本（元）；未配置单价时为 null */
    private String costYuan;

    private Integer avgElapsedMs;

    private Integer avgFirstTokenMs;

    private Long toolCallCount;

    private Long toolFailures;

    /** 结果来自重放的次数（中断续跑的证据） */
    private Long replayedCount;

    private static final long serialVersionUID = 1L;
}
