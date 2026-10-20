package com.xiaohua.star.agent;

import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.config.ChatModelProperties;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ObservabilityService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中断与挂起（M9）：安全点命中即挂起、<b>不误判为失败</b>、标志置位后不再推 delta。见 knowledge/12。
 *
 * <p>纯 JUnit + Mockito，不启 Spring，不连 Redis/DB。
 */
class AgentRunnerSuspendTest {

    private static final long RUN_ID = 100L;
    private static final InterruptTypeEnum STOP = InterruptTypeEnum.USER_STOP;

    @Test
    void 安全点1命中_模型调用前直接挂起_不算失败() {
        AtomicBoolean interrupted = new AtomicBoolean(true);   // 开跑前就已有停止请求
        AgentStateService state = stateMock();
        RecordingSink sink = new RecordingSink();
        AgentRunner runner = runner(state, flag(interrupted));

        AgentRunner.RunResult result = runner.run(
                new FlippingModel(interrupted, ChatResponse.builder().aiMessage(AiMessage.from("你好")).build()),
                ctx(), RUN_ID, 55L, prompt(), sink);

        assertTrue(result.suspended());
        verify(state).suspendRun(RUN_ID, STOP);
        verify(state, never()).beginLlmStep(anyLong(), anyInt(), anyInt(), any());
        verify(state, never()).failRun(anyLong(), any());
        verify(state, never()).finalizeRun(anyLong(), any(), anyInt(), anyInt(), anyInt());
        assertEquals(List.of("run_start", "interrupt"), sink.events);
        assertTrue(sink.deltas.isEmpty());
    }

    @Test
    void 安全点2命中_模型返回后挂起_记LLM步为INTERRUPTED_且抑制delta() {
        AtomicBoolean interrupted = new AtomicBoolean(false);  // SP1 时还没停
        AgentStateService state = stateMock();
        RecordingSink sink = new RecordingSink();
        AgentRunner runner = runner(state, flag(interrupted));

        // FlippingModel 先置位标志（模拟用户在这次调用中途点了停止），再产出一段 delta
        AgentRunner.RunResult result = runner.run(
                new FlippingModel(interrupted, ChatResponse.builder().aiMessage(AiMessage.from("完整回答")).build()),
                ctx(), RUN_ID, 55L, prompt(), sink);

        assertTrue(result.suspended());
        // 本轮 LLM 步被记为 INTERRUPTED（不是 SUCCEEDED），且不是 FAILED
        verify(state).finishLlmStep(anyLong(), any(), anyLong(), eq(AgentStepStatusEnum.INTERRUPTED), isNull());
        verify(state).suspendRun(RUN_ID, STOP);
        verify(state, never()).failRun(anyLong(), any());
        verify(state, never()).finalizeRun(anyLong(), any(), anyInt(), anyInt(), anyInt());
        // 标志置位后不再推 delta
        assertTrue(sink.deltas.isEmpty(), "标志置位后应抑制 delta");
        assertEquals(List.of("run_start", "interrupt"), sink.events);
        assertFalse(result.maxRoundsHit());
    }

    // ---------------- 辅助 ----------------

    private static RunInterruptFlag flag(AtomicBoolean interrupted) {
        RunInterruptFlag flag = mock(RunInterruptFlag.class);
        when(flag.get(anyLong())).thenAnswer(i -> interrupted.get() ? Optional.of(STOP) : Optional.empty());
        return flag;
    }

    private static AgentStateService stateMock() {
        AgentStateService state = mock(AgentStateService.class);
        AtomicLong eventId = new AtomicLong(9000);
        when(state.appendEvent(anyLong(), any(), any(), any())).thenAnswer(i -> eventId.incrementAndGet());
        when(state.beginLlmStep(anyLong(), anyInt(), anyInt(), any())).thenReturn(1L);
        return state;
    }

    private static AgentRunner runner(AgentStateService state, RunInterruptFlag flag) {
        AgentProperties props = new AgentProperties();
        AgentRunner runner = new AgentRunner();
        ReflectionTestUtils.setField(runner, "agentStateService", state);
        ReflectionTestUtils.setField(runner, "conversationMessageService", mock(ConversationMessageService.class));
        ReflectionTestUtils.setField(runner, "toolRegistry", new AgentToolRegistry(List.of()));
        ReflectionTestUtils.setField(runner, "agentProperties", props);
        ReflectionTestUtils.setField(runner, "interruptFlag", flag);
        ReflectionTestUtils.setField(runner, "observabilityService", mock(ObservabilityService.class));
        ReflectionTestUtils.setField(runner, "chatModelProperties", new ChatModelProperties());
        return runner;
    }

    private static List<ChatMessage> prompt() {
        return List.of(SystemMessage.from("SYS"), UserMessage.from("你好"));
    }

    private static RunContext ctx() {
        return new RunContext(1L, 2L, List.of());
    }

    /** 在回调里置位中断标志（模拟「用户在这次模型调用中途点了停止」），再产出一段 delta。 */
    static class FlippingModel implements StreamingChatModel {
        private final AtomicBoolean flag;
        private final ChatResponse response;

        FlippingModel(AtomicBoolean flag, ChatResponse response) {
            this.flag = flag;
            this.response = response;
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            flag.set(true);
            handler.onPartialResponse("半截文字");
            handler.onCompleteResponse(response);
        }
    }

    static class RecordingSink implements AgentEventSink {
        final List<String> events = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();

        @Override
        public void onEvent(long eventId, String eventName, Object data) {
            events.add(eventName);
        }

        @Override
        public void onDelta(String delta) {
            deltas.add(delta);
        }
    }
}
