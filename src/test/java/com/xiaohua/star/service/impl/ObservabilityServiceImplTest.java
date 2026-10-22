package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaohua.star.agent.AgentToolRegistry;
import com.xiaohua.star.agent.ToolMeta;
import com.xiaohua.star.config.ObservabilityProperties;
import com.xiaohua.star.mapper.AiCallLogMapper;
import com.xiaohua.star.mapper.AiToolLogMapper;
import com.xiaohua.star.model.dto.tool.ToolLogQueryRequest;
import com.xiaohua.star.model.entity.AiCallLog;
import com.xiaohua.star.model.entity.AiToolLog;
import com.xiaohua.star.model.vo.CallSummaryVO;
import com.xiaohua.star.model.vo.ToolLogVO;
import com.xiaohua.star.service.ObservabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可观测性写日志 / 查询映射 / 汇总（M12）。纯 Mockito，不连数据库。
 */
class ObservabilityServiceImplTest {

    private AiCallLogMapper callMapper;
    private AiToolLogMapper toolMapper;
    private AgentToolRegistry registry;
    private ObservabilityProperties properties;
    private ObservabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        callMapper = mock(AiCallLogMapper.class);
        toolMapper = mock(AiToolLogMapper.class);
        registry = mock(AgentToolRegistry.class);
        properties = new ObservabilityProperties();
        properties.setMaxSnapshotChars(20);

        service = new ObservabilityServiceImpl();
        ReflectionTestUtils.setField(service, "aiCallLogMapper", callMapper);
        ReflectionTestUtils.setField(service, "aiToolLogMapper", toolMapper);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "toolRegistry", registry);
    }

    @Test
    void 写模型日志_计算成本并截断快照() {
        properties.setInputPricePer1kYuan(new BigDecimal("0.001"));
        properties.setOutputPricePer1kYuan(new BigDecimal("0.002"));

        service.logChatCall(new ObservabilityService.ChatCallContext(
                1L, 2L, 3L, "deepseek", "deepseek-flash", true,
                "{\"lastUserMessage\":\"这是一个很长的请求快照，超过二十个字符\"}", "{\"text\":\"ok\"}",
                1000, 500, 1500, 120L, 800L, true, null));

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callMapper).insert(captor.capture());
        AiCallLog row = captor.getValue();

        assertEquals("CHAT", row.getScene());
        assertEquals("deepseek", row.getProvider());
        assertEquals("deepseek-flash", row.getModelName());
        assertEquals(1, row.getStreaming());
        assertEquals(1, row.getSuccess());
        assertEquals(120L, row.getFirstTokenMs());
        assertEquals(800L, row.getElapsedMs());
        assertEquals(0, new BigDecimal("0.002000").compareTo(row.getCostYuan()));
        assertTrue(row.getRequestJson().contains("已截断"), row.getRequestJson());
        assertEquals(20 + "…（已截断）".length(), row.getRequestJson().length());
    }

    @Test
    void 价格未配置时成本写null() {
        service.logChatCall(new ObservabilityService.ChatCallContext(
                1L, 2L, 3L, "deepseek", "deepseek-flash", true,
                "{}", "{}", 10, 20, 30, null, 100L, true, null));

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callMapper).insert(captor.capture());
        assertNull(captor.getValue().getCostYuan());
        assertNull(captor.getValue().getFirstTokenMs());
    }

    @Test
    void 写工具日志_保留重放与幂等键() {
        service.logToolCall(new ObservabilityService.ToolCallContext(
                2L, 9L, 3L, "createTicket", "INTERNAL", true,
                "{\"title\":\"x\"}", "工单创建成功", false, "失败原因",
                42L, true, "abc123"));

        ArgumentCaptor<AiToolLog> captor = ArgumentCaptor.forClass(AiToolLog.class);
        verify(toolMapper).insert(captor.capture());
        AiToolLog row = captor.getValue();

        assertEquals(2L, row.getRunId());
        assertEquals(9L, row.getStepId());
        assertEquals("INTERNAL", row.getSource());
        assertEquals(1, row.getSideEffect());
        assertEquals(0, row.getSuccess());
        assertEquals(1, row.getReplayed());
        assertEquals("abc123", row.getIdempotencyKey());
        assertEquals("失败原因", row.getErrorMsg());
    }

    @Test
    void 写日志失败不影响主流程() {
        when(callMapper.insert(any(AiCallLog.class))).thenThrow(new RuntimeException("db down"));
        when(toolMapper.insert(any(AiToolLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.logChatCall(new ObservabilityService.ChatCallContext(
                null, null, null, null, null, false,
                null, null, null, null, null, null, 0, true, null)));
        assertDoesNotThrow(() -> service.logToolCall(new ObservabilityService.ToolCallContext(
                null, null, null, "t", null, false,
                null, null, true, null, 0, false, null)));
    }

    @Test
    void 工具日志分页映射成VO() {
        AiToolLog row = new AiToolLog();
        row.setId(11L);
        row.setRunId(22L);
        row.setToolName("searchKnowledge");
        row.setSideEffect(0);
        row.setSuccess(1);
        row.setArgumentsJson("{\"query\":\"年假\"}");
        row.setResultJson("检索结果");
        row.setElapsedMs(123L);
        row.setReplayed(1);
        row.setCreateTime(LocalDateTime.now());

        Page<AiToolLog> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(toolMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        ToolMeta meta = mock(ToolMeta.class);
        when(meta.displayName()).thenReturn("检索知识库");
        when(registry.meta("searchKnowledge")).thenReturn(meta);

        IPage<ToolLogVO> result = service.listToolLogs(new ToolLogQueryRequest(), 3L);

        assertEquals(1, result.getRecords().size());
        ToolLogVO vo = result.getRecords().get(0);
        assertEquals(11L, vo.getId());
        assertEquals("检索知识库", vo.getDisplayName());
        assertFalse(vo.getSideEffect());
        assertTrue(vo.getSuccess());
        assertTrue(vo.getReplayed());
        assertEquals(123, vo.getElapsedMs());
        assertEquals(Map.of("query", "年假"), vo.getArguments());
        assertEquals(Map.of("text", "检索结果"), vo.getResult());
    }

    @Test
    void 汇总解析计数字段与成本() {
        properties.setInputPricePer1kYuan(new BigDecimal("0.001"));
        properties.setOutputPricePer1kYuan(new BigDecimal("0.002"));

        when(callMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of(
                "callCount", 3L,
                "promptTokens", 3000L,
                "completionTokens", 1500L,
                "totalTokens", 4500L,
                "costYuan", new BigDecimal("0.006000"),
                "avgElapsedMs", new BigDecimal("800.4"),
                "avgFirstTokenMs", new BigDecimal("120.6"))));
        when(toolMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of(
                "toolCallCount", 2L,
                "toolFailures", 1L,
                "replayedCount", 1L)));

        CallSummaryVO vo = service.summary(3L, 9L);

        assertEquals(9L, vo.getRunId());
        assertEquals(3L, vo.getCallCount());
        assertEquals(3000L, vo.getPromptTokens());
        assertEquals(1500L, vo.getCompletionTokens());
        assertEquals(4500L, vo.getTotalTokens());
        assertEquals("0.006", vo.getCostYuan());
        assertEquals(800, vo.getAvgElapsedMs());
        assertEquals(121, vo.getAvgFirstTokenMs());
        assertEquals(2L, vo.getToolCallCount());
        assertEquals(1L, vo.getToolFailures());
        assertEquals(1L, vo.getReplayedCount());
    }
}
