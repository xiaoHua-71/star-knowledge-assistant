package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.agent.AgentToolRegistry;
import com.xiaohua.star.agent.ToolMeta;
import com.xiaohua.star.common.CostCalculator;
import com.xiaohua.star.config.ObservabilityProperties;
import com.xiaohua.star.mapper.AiCallLogMapper;
import com.xiaohua.star.mapper.AiToolLogMapper;
import com.xiaohua.star.model.dto.tool.ToolLogQueryRequest;
import com.xiaohua.star.model.entity.AiCallLog;
import com.xiaohua.star.model.entity.AiToolLog;
import com.xiaohua.star.model.vo.CallSummaryVO;
import com.xiaohua.star.model.vo.ToolLogVO;
import com.xiaohua.star.service.ObservabilityService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 可观测性实现（M12）。见 knowledge/50。
 *
 * <p>两个写入口都<b>不在事务里</b>：单条 INSERT 自动提交即可；更重要的是，
 * 日志失败绝不能回滚或中断正在跑的 Agent 运行。异常在这里被吞掉，只打 warn。
 */
@Slf4j
@Service
public class ObservabilityServiceImpl implements ObservabilityService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AiCallLogMapper aiCallLogMapper;

    @Resource
    private AiToolLogMapper aiToolLogMapper;

    @Resource
    private ObservabilityProperties properties;

    @Resource
    private AgentToolRegistry toolRegistry;

    @Override
    public void logChatCall(ChatCallContext c) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            AiCallLog row = new AiCallLog();
            row.setConversationId(c.conversationId());
            row.setRunId(c.runId());
            row.setUserId(c.userId());
            row.setScene("CHAT");
            row.setProvider(c.provider() == null || c.provider().isBlank() ? "unknown" : c.provider());
            row.setModelName(c.modelName() == null || c.modelName().isBlank() ? "unknown" : c.modelName());
            row.setStreaming(c.streaming() ? 1 : 0);
            row.setRequestJson(truncate(c.requestJson()));
            row.setResponseJson(truncate(c.responseJson()));
            row.setPromptTokens(c.promptTokens());
            row.setCompletionTokens(c.completionTokens());
            row.setTotalTokens(c.totalTokens());
            row.setCostYuan(CostCalculator.calculate(c.promptTokens(), c.completionTokens(),
                    properties.getInputPricePer1kYuan(), properties.getOutputPricePer1kYuan()));
            row.setFirstTokenMs(c.firstTokenMs());
            row.setElapsedMs(c.elapsedMs());
            row.setSuccess(c.success() ? 1 : 0);
            row.setErrorMsg(c.errorMsg());
            aiCallLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("写 ai_call_log 失败（不影响运行）: runId={}, err={}", c.runId(), e.getMessage());
        }
    }

    @Override
    public void logToolCall(ToolCallContext c) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            AiToolLog row = new AiToolLog();
            row.setRunId(c.runId());
            row.setStepId(c.stepId());
            row.setUserId(c.userId());
            row.setToolName(c.toolName());
            row.setSource(c.source() == null || c.source().isBlank() ? "LOCAL" : c.source());
            row.setSideEffect(c.sideEffect() ? 1 : 0);
            row.setArgumentsJson(truncate(c.argumentsJson()));
            row.setResultJson(truncate(c.resultJson()));
            row.setSuccess(c.success() ? 1 : 0);
            row.setErrorMsg(c.errorMsg());
            row.setElapsedMs(c.elapsedMs());
            row.setReplayed(c.replayed() ? 1 : 0);
            row.setIdempotencyKey(c.idempotencyKey());
            aiToolLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("写 ai_tool_log 失败（不影响运行）: runId={}, tool={}, err={}",
                    c.runId(), c.toolName(), e.getMessage());
        }
    }

    @Override
    public IPage<ToolLogVO> listToolLogs(ToolLogQueryRequest request, Long userId) {
        Page<AiToolLog> page = new Page<>(
                Math.max(request.getCurrent(), 1),
                Math.min(Math.max(request.getPageSize(), 1), 100));

        QueryWrapper<AiToolLog> wrapper = new QueryWrapper<>();
        wrapper.eq("userId", userId);
        if (request.getRunId() != null) {
            wrapper.eq("runId", request.getRunId());
        }
        if (request.getToolName() != null && !request.getToolName().isBlank()) {
            wrapper.eq("toolName", request.getToolName().trim());
        }
        if (request.getSuccess() != null) {
            wrapper.eq("success", request.getSuccess() ? 1 : 0);
        }
        wrapper.orderByDesc("id");

        return aiToolLogMapper.selectPage(page, wrapper).convert(this::toToolLogVO);
    }

    @Override
    public CallSummaryVO summary(Long userId, Long runId) {
        Map<String, Object> call = firstRow(aiCallLogMapper.selectMaps(callSummaryQuery(userId, runId)));
        Map<String, Object> tool = firstRow(aiToolLogMapper.selectMaps(toolSummaryQuery(userId, runId)));

        CallSummaryVO vo = new CallSummaryVO();
        vo.setRunId(runId);
        vo.setCallCount(asLong(call, "callCount"));
        vo.setPromptTokens(asLong(call, "promptTokens"));
        vo.setCompletionTokens(asLong(call, "completionTokens"));
        vo.setTotalTokens(asLong(call, "totalTokens"));
        vo.setAvgElapsedMs(asInt(call, "avgElapsedMs"));
        vo.setAvgFirstTokenMs(asInt(call, "avgFirstTokenMs"));
        vo.setToolCallCount(asLong(tool, "toolCallCount"));
        vo.setToolFailures(asLong(tool, "toolFailures"));
        vo.setReplayedCount(asLong(tool, "replayedCount"));

        boolean priceConfigured = properties.getInputPricePer1kYuan().signum() != 0
                || properties.getOutputPricePer1kYuan().signum() != 0;
        vo.setCostYuan(priceConfigured ? CostCalculator.display(asDecimal(call, "costYuan")) : null);
        return vo;
    }

    // ---------------- 内部 ----------------

    private static QueryWrapper<AiCallLog> callSummaryQuery(Long userId, Long runId) {
        QueryWrapper<AiCallLog> wrapper = new QueryWrapper<>();
        wrapper.select(
                "COUNT(*) AS callCount",
                "COALESCE(SUM(promptTokens), 0) AS promptTokens",
                "COALESCE(SUM(completionTokens), 0) AS completionTokens",
                "COALESCE(SUM(totalTokens), 0) AS totalTokens",
                "COALESCE(SUM(costYuan), 0) AS costYuan",
                "COALESCE(AVG(elapsedMs), 0) AS avgElapsedMs",
                "COALESCE(AVG(firstTokenMs), 0) AS avgFirstTokenMs");
        wrapper.eq("userId", userId);
        if (runId != null) {
            wrapper.eq("runId", runId);
        }
        return wrapper;
    }

    private static QueryWrapper<AiToolLog> toolSummaryQuery(Long userId, Long runId) {
        QueryWrapper<AiToolLog> wrapper = new QueryWrapper<>();
        wrapper.select(
                "COUNT(*) AS toolCallCount",
                "COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS toolFailures",
                "COALESCE(SUM(CASE WHEN replayed = 1 THEN 1 ELSE 0 END), 0) AS replayedCount");
        wrapper.eq("userId", userId);
        if (runId != null) {
            wrapper.eq("runId", runId);
        }
        return wrapper;
    }

    private ToolLogVO toToolLogVO(AiToolLog row) {
        ToolLogVO vo = new ToolLogVO();
        vo.setId(row.getId());
        vo.setRunId(row.getRunId());
        vo.setToolName(row.getToolName());
        ToolMeta meta = toolRegistry.meta(row.getToolName());
        vo.setDisplayName(meta == null ? row.getToolName() : meta.displayName());
        vo.setSideEffect(row.getSideEffect() != null && row.getSideEffect() == 1);
        vo.setSuccess(row.getSuccess() == null || row.getSuccess() == 1);
        vo.setArguments(parseObject(row.getArgumentsJson(), null));
        vo.setResult(parseObject(row.getResultJson(), null));
        vo.setErrorMsg(row.getErrorMsg());
        vo.setElapsedMs(row.getElapsedMs() == null ? null : clampInt(row.getElapsedMs()));
        vo.setReplayed(row.getReplayed() != null && row.getReplayed() == 1);
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }

    private String truncate(String text) {
        int max = properties.getMaxSnapshotChars();
        if (text == null || max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…（已截断）";
    }

    /** 解析 JSON；非 JSON 文本包成 {@code {"text": 原文}}，坏数据回退 fallback。 */
    private static Object parseObject(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of("text", json);
        }
    }

    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static long asLong(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static int asInt(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number n) {
            return clampInt(Math.round(n.doubleValue()));
        }
        return 0;
    }

    private static BigDecimal asDecimal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private static int clampInt(long v) {
        return (int) Math.min(Math.max(v, Integer.MIN_VALUE), Integer.MAX_VALUE);
    }
}
