package com.xiaohua.star.agent;

import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时封存（M13）：条件翻转 + 半截消息的两条护栏。纯 Mockito，不连数据库。
 */
class RunTimeoutScannerTest {

    private static final String LONG_PARTIAL = "这个问题我分三步说明：先检查网络，再检查 DNS，最后看代理。";

    private AgentStateService agentStateService;
    private ConversationMessageService conversationMessageService;
    private AgentProperties properties;
    private RunTimeoutScanner scanner;

    @BeforeEach
    void setUp() {
        agentStateService = mock(AgentStateService.class);
        conversationMessageService = mock(ConversationMessageService.class);
        properties = new AgentProperties();

        scanner = new RunTimeoutScanner();
        ReflectionTestUtils.setField(scanner, "agentStateService", agentStateService);
        ReflectionTestUtils.setField(scanner, "conversationMessageService", conversationMessageService);
        ReflectionTestUtils.setField(scanner, "agentProperties", properties);
    }

    @Test
    void 超过保留期则封存并落interrupted助手消息() {
        givenExpiredRun();
        when(agentStateService.cancelRunIfSuspended(1L)).thenReturn(true);
        when(agentStateService.listSteps(1L)).thenReturn(List.of(interruptedLlmStep(LONG_PARTIAL)));
        when(conversationMessageService.listPromptVisibleAsc(10L)).thenReturn(List.of(message(51L)));

        scanner.scan();

        verify(agentStateService).cancelRunIfSuspended(1L);
        verify(conversationMessageService).appendMessage(
                eq(10L), eq(1L), eq(ConversationMessage.ROLE_ASSISTANT),
                eq(LONG_PARTIAL), isNull(), eq(1L), eq(true));
    }

    @Test
    void 半截文本过短只封存不落消息() {
        givenExpiredRun();
        when(agentStateService.cancelRunIfSuspended(1L)).thenReturn(true);
        when(agentStateService.listSteps(1L)).thenReturn(List.of(interruptedLlmStep("好的，那")));

        scanner.scan();

        verify(agentStateService).cancelRunIfSuspended(1L);
        verify(conversationMessageService, never()).appendMessage(
                anyLong(), anyLong(), anyString(), anyString(), any(), anyLong(), eq(true));
    }

    @Test
    void 输入消息已不是最新时不落消息() {
        givenExpiredRun();
        when(agentStateService.cancelRunIfSuspended(1L)).thenReturn(true);
        when(agentStateService.listSteps(1L)).thenReturn(List.of(interruptedLlmStep(LONG_PARTIAL)));
        when(conversationMessageService.listPromptVisibleAsc(10L))
                .thenReturn(List.of(message(51L), message(77L)));

        scanner.scan();

        verify(agentStateService).cancelRunIfSuspended(1L);
        verify(conversationMessageService, never()).appendMessage(
                anyLong(), anyLong(), anyString(), anyString(), any(), anyLong(), eq(true));
    }

    @Test
    void 已被并发继续时不查步骤也不落消息() {
        givenExpiredRun();
        when(agentStateService.cancelRunIfSuspended(1L)).thenReturn(false);

        scanner.scan();

        verify(agentStateService).cancelRunIfSuspended(1L);
        verify(agentStateService, never()).listSteps(anyLong());
        verify(conversationMessageService, never()).appendMessage(
                anyLong(), anyLong(), anyString(), anyString(), any(), anyLong(), eq(true));
    }

    @Test
    void 数据库异常不向外抛() {
        when(agentStateService.listSuspendedBefore(any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> scanner.scan());
    }

    @Test
    void 单条封存失败不影响其他运行() {
        AgentRun bad = run(1L);
        AgentRun good = run(2L);
        when(agentStateService.listSuspendedBefore(any(), anyInt())).thenReturn(List.of(bad, good));
        when(agentStateService.cancelRunIfSuspended(1L)).thenThrow(new RuntimeException("boom"));
        when(agentStateService.cancelRunIfSuspended(2L)).thenReturn(true);
        when(agentStateService.listSteps(2L)).thenReturn(List.of(interruptedLlmStep("短")));

        assertDoesNotThrow(() -> scanner.scan());

        verify(agentStateService).cancelRunIfSuspended(2L);
    }

    private void givenExpiredRun() {
        when(agentStateService.listSuspendedBefore(any(), anyInt())).thenReturn(List.of(run(1L)));
    }

    private static AgentRun run(long id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setConversationId(10L);
        run.setUserId(1L);
        run.setInputMessageId(51L);
        return run;
    }

    private static AgentStep interruptedLlmStep(String text) {
        AgentStep step = new AgentStep();
        step.setType("LLM_CALL");
        step.setStatus("INTERRUPTED");
        step.setResponseJson("{\"text\":\"" + text + "\"}");
        return step;
    }

    private static ConversationMessage message(Long id) {
        ConversationMessage m = new ConversationMessage();
        m.setId(id);
        return m;
    }
}
