package com.xiaohua.star.agent;

import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.config.ChatModelProperties;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ObservabilityService;
import dev.langchain4j.agent.tool.P;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * 人工确认与幂等三分支（M10）。纯 JUnit + Mockito，不连 Redis/DB。
 * 见 knowledge/12 ③ 幂等键、④ 场景 B、⑤ 坑。
 */
class AgentRunnerApprovalTest {

    private static final long RUN_ID = 100L;

    @Test
    void 需确认的副作用工具_挂起且不执行() {
        ApprovalTool tool = new ApprovalTool();
        AgentStateService state = stateMock(AgentStateService.ClaimResult.CLAIMED);
        RecordingSink sink = new RecordingSink();

        AgentRunner.RunResult result = runner(state, tool).run(
                model(toolCall("createTicket", "{\"title\":\"测试\"}")), ctx(), RUN_ID, 55L, prompt(), sink);

        assertTrue(result.suspended());
        assertEquals(0, tool.calls.get(), "未确认前绝不能执行副作用工具");
        verify(state).markWaitingApproval(RUN_ID, 1);
        verify(state, never()).claimToolStep(anyLong(), anyString());
        assertEquals(List.of("run_start", "tool_call", "interrupt"), sink.events);
        assertTrue(sink.lastEventData.toString().contains("APPROVAL_REQUIRED"), sink.lastEventData.toString());
        assertTrue(sink.lastEventData.toString().contains("createTicket"), sink.lastEventData.toString());
        assertTrue(sink.lastEventData.toString().contains("测试"), "确认框必须带原始参数");
    }

    @Test
    void 幂等的副作用工具_自动执行并写幂等键() {
        AutoIdempotentTool tool = new AutoIdempotentTool();
        AgentStateService state = stateMock(AgentStateService.ClaimResult.CLAIMED);
        RecordingSink sink = new RecordingSink();

        AgentRunner.RunResult result = runner(state, tool).run(
                model(toolCall("autoSideEffect", "{}"), finalAnswer("完成")),
                ctx(), RUN_ID, 55L, prompt(), sink);

        assertFalse(result.suspended());
        assertEquals(1, tool.calls.get());
        assertEquals(2, result.rounds());
        verify(state).claimToolStep(eq(7L), anyString());
        verify(state, never()).markWaitingApproval(anyLong(), anyInt());
        assertEquals(List.of("run_start", "tool_call", "tool_result", "run_end"), sink.events);
    }

    @Test
    void 撞键且已有结果_重放原结果_不重跑() {
        AutoIdempotentTool tool = new AutoIdempotentTool();
        AgentStateService state = stateMock(AgentStateService.ClaimResult.NOT_CLAIMABLE);
        when(state.getStep(7L)).thenReturn(step(7L, AgentStepStatusEnum.SUCCEEDED, "cached-result"));
        RecordingSink sink = new RecordingSink();

        AgentRunner.RunResult result = runner(state, tool).run(
                model(toolCall("autoSideEffect", "{}"), finalAnswer("完成")),
                ctx(), RUN_ID, 55L, prompt(), sink);

        assertFalse(result.suspended());
        assertEquals(0, tool.calls.get(), "有结果必须重放，不能重新执行");
        verify(state).markReplayed(7L);
        verify(state, never()).finishToolStep(anyLong(), anyString(), anyLong(), any(), any());
        assertEquals(List.of("run_start", "tool_call", "tool_result", "run_end"), sink.events);
    }

    @Test
    void 撞键且结果未知_转人工确认_绝不自动重跑() {
        AutoIdempotentTool tool = new AutoIdempotentTool();
        AgentStateService state = stateMock(AgentStateService.ClaimResult.NOT_CLAIMABLE);
        when(state.getStep(7L)).thenReturn(step(7L, AgentStepStatusEnum.RUNNING, null));
        RecordingSink sink = new RecordingSink();

        AgentRunner.RunResult result = runner(state, tool).run(
                model(toolCall("autoSideEffect", "{}")), ctx(), RUN_ID, 55L, prompt(), sink);

        assertTrue(result.suspended());
        assertEquals(0, tool.calls.get());
        verify(state).markWaitingApproval(RUN_ID, 1);
        verify(state, never()).finishToolStep(anyLong(), anyString(), anyLong(), any(), any());
        assertTrue(sink.lastEventData.toString().contains("未知"), sink.lastEventData.toString());
    }

    @Test
    void 撞键在另一行且有结果_回填当前意图行并重放() {
        AutoIdempotentTool tool = new AutoIdempotentTool();
        AgentStateService state = stateMock(AgentStateService.ClaimResult.KEY_CONFLICT);
        when(state.findStepByIdempotencyKey(anyString()))
                .thenReturn(step(99L, AgentStepStatusEnum.SUCCEEDED, "cached-result"));
        RecordingSink sink = new RecordingSink();

        AgentRunner.RunResult result = runner(state, tool).run(
                model(toolCall("autoSideEffect", "{}"), finalAnswer("完成")),
                ctx(), RUN_ID, 55L, prompt(), sink);

        assertFalse(result.suspended());
        assertEquals(0, tool.calls.get());
        // 当前意图行（7）用已有结果回填，避免留下永远 PENDING 的孤儿步骤
        verify(state).finishToolStep(eq(7L), eq("cached-result"), anyLong(),
                eq(AgentStepStatusEnum.SUCCEEDED), isNull());
        verify(state).markReplayed(7L);
    }

    // ---------------- 工具与模型替身 ----------------

    static class ApprovalTool implements AgentToolPack {
        final AtomicInteger calls = new AtomicInteger();

        @Tool("创建工单")
        @ToolMeta(displayName = "创建工单", sideEffect = true, requireApproval = true)
        public String createTicket(@P(name = "title", description = "标题") String title) {
            calls.incrementAndGet();
            return "T-1";
        }
    }

    static class AutoIdempotentTool implements AgentToolPack {
        final AtomicInteger calls = new AtomicInteger();

        @Tool("自动副作用")
        @ToolMeta(displayName = "自动副作用", sideEffect = true, idempotentBackend = true)
        public String autoSideEffect() {
            calls.incrementAndGet();
            return "ok";
        }
    }

    static class ScriptedModel implements StreamingChatModel {
        private final List<ChatResponse> script;
        private int call = 0;

        ScriptedModel(ChatResponse... responses) {
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

    static class RecordingSink implements AgentEventSink {
        final List<String> events = new ArrayList<>();
        Object lastEventData;

        @Override
        public void onEvent(long eventId, String eventName, Object data) {
            events.add(eventName);
            lastEventData = data;
        }

        @Override
        public void onDelta(String delta) {
        }
    }

    // ---------------- 辅助 ----------------

    private static AgentStateService stateMock(AgentStateService.ClaimResult claim) {
        AgentStateService state = mock(AgentStateService.class);
        AtomicLong eventId = new AtomicLong(9000);
        when(state.appendEvent(anyLong(), any(), any(), anyString()))
                .thenAnswer(i -> eventId.incrementAndGet());
        when(state.beginLlmStep(anyLong(), anyInt(), anyInt(), any())).thenReturn(1L);
        when(state.beginToolStep(anyLong(), anyInt(), anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean())).thenReturn(7L);
        when(state.markWaitingApproval(anyLong(), anyInt())).thenReturn(true);
        when(state.claimToolStep(anyLong(), anyString())).thenReturn(claim);
        return state;
    }

    private static AgentRunner runner(AgentStateService state, AgentToolPack pack) {
        AgentProperties props = new AgentProperties();
        AgentRunner runner = new AgentRunner();
        ReflectionTestUtils.setField(runner, "agentStateService", state);
        ReflectionTestUtils.setField(runner, "conversationMessageService", messageServiceMock());
        ReflectionTestUtils.setField(runner, "toolRegistry", new AgentToolRegistry(List.of(pack)));
        ReflectionTestUtils.setField(runner, "agentProperties", props);
        ReflectionTestUtils.setField(runner, "interruptFlag", noInterrupt());
        ReflectionTestUtils.setField(runner, "observabilityService", mock(ObservabilityService.class));
        ReflectionTestUtils.setField(runner, "chatModelProperties", new ChatModelProperties());
        return runner;
    }

    private static ConversationMessageService messageServiceMock() {
        ConversationMessageService cms = mock(ConversationMessageService.class);
        ConversationMessage saved = new ConversationMessage();
        saved.setId(200L);
        when(cms.appendMessage(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(saved);
        return cms;
    }

    private static RunInterruptFlag noInterrupt() {
        RunInterruptFlag flag = mock(RunInterruptFlag.class);
        when(flag.get(anyLong())).thenReturn(java.util.Optional.empty());
        return flag;
    }

    private static ChatResponse toolCall(String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id("call_1").name(name).arguments(arguments).build())).build();
    }

    private static ChatResponse finalAnswer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static ScriptedModel model(ChatResponse... responses) {
        return new ScriptedModel(responses);
    }

    private static AgentStep step(long id, AgentStepStatusEnum status, String result) {
        AgentStep s = new AgentStep();
        s.setId(id);
        s.setRunId(RUN_ID);
        s.setStepNo(1);
        s.setSeq(2);
        s.setType(com.xiaohua.star.model.enums.AgentStepTypeEnum.TOOL_CALL.name());
        s.setStatus(status.name());
        s.setToolCallId("call_1");
        s.setToolName("autoSideEffect");
        s.setArgumentsJson("{}");
        s.setToolResultJson(result);
        s.setSideEffect(1);
        s.setRequireApproval(0);
        return s;
    }

    private static List<ChatMessage> prompt() {
        return List.of(SystemMessage.from("SYS"), UserMessage.from("你好"));
    }

    private static RunContext ctx() {
        return new RunContext(1L, 2L, List.of());
    }
}
