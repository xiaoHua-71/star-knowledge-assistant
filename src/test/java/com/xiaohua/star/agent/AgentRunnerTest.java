package com.xiaohua.star.agent;

import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.config.ChatModelProperties;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentEventTypeEnum;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ObservabilityService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentRunner 落库循环（M8）：落库调用顺序、工具意图先落库再执行、护栏、事件类型。
 *
 * <p>用 mock 的 {@link AgentStateService} 当持久化门面（不连数据库），假的 {@link StreamingChatModel}
 * 驱动循环，真 {@link AgentToolRegistry} 执行工具。关键断言用<b>共享的调用序列</b>验证「意图先落库、后执行」。
 */
class AgentRunnerTest {

    private static final long RUN_ID = 100L;

    @Test
    void 先调工具再给最终回答并落库() {
        List<String> calls = new ArrayList<>();
        AgentStateService state = stateMock(calls);
        ConversationMessageService cms = messageServiceMock();
        AgentEventSink sink = new RecordingSink();

        FakeModel model = new FakeModel(
                toolCallResponse("ping"),
                ChatResponse.builder().aiMessage(AiMessage.from("完成")).build());

        AgentRunner runner = runner(state, cms, new AgentToolRegistry(List.of(new RecordingTool(calls))), 8);
        AgentRunner.RunResult result = runner.run(model, ctx(), RUN_ID, 55L, prompt(), sink);

        assertEquals(RUN_ID, result.runId());
        assertEquals("完成", result.finalText());
        assertEquals(2, result.rounds());
        assertFalse(result.maxRoundsHit());
        assertFalse(result.suspended());

        // ★ 核心不变量：工具意图行必须先落库，再执行工具
        assertTrue(calls.indexOf("beginToolStep") < calls.indexOf("toolExec"),
                "工具必须先落意图行再执行，实际序列: " + calls);

        // 事件类型与顺序（落库并推送）
        assertEquals(List.of("run_start", "tool_call", "tool_result", "run_end"),
                ((RecordingSink) sink).events);
        verify(state).finalizeRun(eq(RUN_ID), eq(200L), anyInt(), anyInt(), eq(2));
        verify(state, times(2)).beginLlmStep(anyLong(), anyInt(), anyInt(), any());
        verify(state, times(1)).beginToolStep(anyLong(), anyInt(), anyInt(), any(), eq("ping"), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void 模型与工具调用都写可观测性日志() {
        List<String> calls = new ArrayList<>();
        AgentStateService state = stateMock(calls);
        ConversationMessageService cms = messageServiceMock();
        ObservabilityService obs = mock(ObservabilityService.class);

        FakeModel model = new FakeModel(
                toolCallResponse("ping"),
                ChatResponse.builder().aiMessage(AiMessage.from("完成")).build());

        AgentRunner runner = runner(state, cms,
                new AgentToolRegistry(List.of(new RecordingTool(calls))), 8, obs);
        runner.run(model, ctx(), RUN_ID, 55L, prompt(), new RecordingSink());

        ArgumentCaptor<ObservabilityService.ChatCallContext> chat =
                ArgumentCaptor.forClass(ObservabilityService.ChatCallContext.class);
        verify(obs, times(2)).logChatCall(chat.capture());
        assertEquals(RUN_ID, chat.getAllValues().get(0).runId());
        assertEquals("deepseek", chat.getAllValues().get(0).provider());
        assertNotNull(chat.getAllValues().get(1).firstTokenMs(), "最终回答有 delta，应记首 token 延迟");

        ArgumentCaptor<ObservabilityService.ToolCallContext> tool =
                ArgumentCaptor.forClass(ObservabilityService.ToolCallContext.class);
        verify(obs).logToolCall(tool.capture());
        assertEquals("ping", tool.getValue().toolName());
        assertFalse(tool.getValue().sideEffect());
        assertFalse(tool.getValue().replayed());
    }

    @Test
    void 每轮都要求调工具时在护栏处停下并仍收尾() {
        List<String> calls = new ArrayList<>();
        AgentStateService state = stateMock(calls);
        ConversationMessageService cms = messageServiceMock();

        FakeModel model = new FakeModel(toolCallResponse("ping")); // 永远要求调工具

        AgentRunner runner = runner(state, cms, new AgentToolRegistry(List.of(new RecordingTool(calls))), 3);
        AgentRunner.RunResult result = runner.run(model, ctx(), RUN_ID, 55L, prompt(), new RecordingSink());

        assertTrue(result.maxRoundsHit());
        assertEquals(3, result.rounds());
        assertEquals(AgentRunner.MAX_ROUNDS_MESSAGE, result.finalText());
        verify(state, times(3)).beginToolStep(anyLong(), anyInt(), anyInt(), any(), eq("ping"), any(), anyBoolean(), anyBoolean());
        // 撞护栏也要收尾（落助手消息 + run COMPLETED）
        verify(state).finalizeRun(eq(RUN_ID), eq(200L), anyInt(), anyInt(), eq(3));
        verify(state).appendEvent(anyLong(), any(), eq(AgentEventTypeEnum.RUN_END), anyString());
    }

    // ---------------- 辅助 ----------------

    private static AgentStateService stateMock(List<String> calls) {
        AgentStateService state = mock(AgentStateService.class);
        AtomicLong eventId = new AtomicLong(9000);
        when(state.appendEvent(anyLong(), any(), any(), anyString()))
                .thenAnswer(i -> eventId.incrementAndGet());
        when(state.beginLlmStep(anyLong(), anyInt(), anyInt(), any())).thenReturn(1L);
        when(state.beginToolStep(anyLong(), anyInt(), anyInt(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(i -> {
                    calls.add("beginToolStep");
                    return 7L;
                });
        return state;
    }

    /** 不触发中断的标志（{@code get} 恒 empty）。 */
    static RunInterruptFlag noInterrupt() {
        RunInterruptFlag flag = mock(RunInterruptFlag.class);
        when(flag.get(anyLong())).thenReturn(java.util.Optional.empty());
        return flag;
    }

    private static ConversationMessageService messageServiceMock() {
        ConversationMessageService cms = mock(ConversationMessageService.class);
        ConversationMessage saved = new ConversationMessage();
        saved.setId(200L);
        saved.setSeq(2);
        when(cms.appendMessage(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(saved);
        return cms;
    }

    private static AgentRunner runner(AgentStateService state, ConversationMessageService cms,
                                      AgentToolRegistry registry, int maxRounds) {
        return runner(state, cms, registry, maxRounds, mock(ObservabilityService.class));
    }

    private static AgentRunner runner(AgentStateService state, ConversationMessageService cms,
                                      AgentToolRegistry registry, int maxRounds,
                                      ObservabilityService observabilityService) {
        AgentProperties props = new AgentProperties();
        props.setMaxRounds(maxRounds);
        AgentRunner runner = new AgentRunner();
        ReflectionTestUtils.setField(runner, "agentStateService", state);
        ReflectionTestUtils.setField(runner, "conversationMessageService", cms);
        ReflectionTestUtils.setField(runner, "toolRegistry", registry);
        ReflectionTestUtils.setField(runner, "agentProperties", props);
        ReflectionTestUtils.setField(runner, "interruptFlag", noInterrupt());
        ReflectionTestUtils.setField(runner, "observabilityService", observabilityService);
        ReflectionTestUtils.setField(runner, "chatModelProperties", new ChatModelProperties());
        return runner;
    }

    private static ChatResponse toolCallResponse(String toolName) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call_1").name(toolName).arguments("{}").build()))
                .build();
    }

    private static List<ChatMessage> prompt() {
        return List.of(SystemMessage.from("SYS"), UserMessage.from("你好"));
    }

    private static RunContext ctx() {
        return new RunContext(1L, 2L, List.of());
    }

    /** 记录「被执行」的工具，供顺序断言。 */
    static class RecordingTool implements AgentToolPack {
        private final List<String> calls;

        RecordingTool(List<String> calls) {
            this.calls = calls;
        }

        @Tool("记录调用顺序")
        @ToolMeta(displayName = "记录")
        public String ping() {
            calls.add("toolExec");
            return "pong";
        }
    }

    /** 记录事件与 delta 的出口。 */
    static class RecordingSink implements AgentEventSink {
        final List<String> events = new ArrayList<>();

        @Override
        public void onEvent(long eventId, String eventName, Object data) {
            events.add(eventName);
        }

        @Override
        public void onDelta(String delta) {
        }
    }

    static class FakeModel implements StreamingChatModel {
        private final List<ChatResponse> script;
        private int call = 0;

        FakeModel(ChatResponse... responses) {
            this.script = List.of(responses);
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            ChatResponse response = script.get(Math.min(call, script.size() - 1));
            call++;
            String text = response.aiMessage().text();
            if (text != null && !text.isEmpty()) {
                handler.onPartialResponse(text);
            }
            handler.onCompleteResponse(response);
        }
    }
}
