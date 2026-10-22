package com.xiaohua.star.model.dto.agent;

import lombok.Data;

/**
 * 人工确认请求。对应 {@code POST /agent/run/{runId}/approve}（见 frontend/40）。
 *
 * <p>{@code approve=true} 同意执行；{@code false} 拒绝。拒绝原因会作为工具错误结果回给模型，
 * 所以模型知道「用户为什么不同意」，可以换个思路。
 */
@Data
public class AgentApproveRequest {

    /** 必填：true = 同意；false = 拒绝 */
    private Boolean approve;

    /** 拒绝原因，最长 500 字符（仅拒绝时有意义） */
    private String reason;
}
