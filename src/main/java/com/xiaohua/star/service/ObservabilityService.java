package com.xiaohua.star.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaohua.star.model.dto.tool.ToolLogQueryRequest;
import com.xiaohua.star.model.vo.CallSummaryVO;
import com.xiaohua.star.model.vo.ToolLogVO;

/**
 * 可观测性：LLM / 工具调用日志与汇总（M12）。见 knowledge/50。
 *
 * <p><b>写日志绝不影响主流程</b>：实现类内部吞掉所有异常（只打 warn），
 * 因为「记录失败」不应该让一次本来成功的对话失败。
 */
public interface ObservabilityService {

    /** 一次 LLM 调用的上下文。 */
    record ChatCallContext(
            Long conversationId, Long runId, Long userId,
            String provider, String modelName, boolean streaming,
            String requestJson, String responseJson,
            Integer promptTokens, Integer completionTokens, Integer totalTokens,
            Long firstTokenMs, long elapsedMs,
            boolean success, String errorMsg) {
    }

    /** 一次工具调用（真实执行或重放）的上下文。 */
    record ToolCallContext(
            Long runId, Long stepId, Long userId,
            String toolName, String source, boolean sideEffect,
            String argumentsJson, String resultJson,
            boolean success, String errorMsg,
            long elapsedMs, boolean replayed, String idempotencyKey) {
    }

    void logChatCall(ChatCallContext context);

    void logToolCall(ToolCallContext context);

    /** 当前用户的工具调用记录（分页）。 */
    IPage<ToolLogVO> listToolLogs(ToolLogQueryRequest request, Long userId);

    /** 当前用户的调用汇总；{@code runId} 可空。 */
    CallSummaryVO summary(Long userId, Long runId);
}
