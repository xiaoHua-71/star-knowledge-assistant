package com.xiaohua.star.agent;

import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续跑消息重建（M9）：只重放「完整轮」，孤儿 tool_call 绝不重放。见 knowledge/12。
 *
 * <p>纯逻辑，不启 Spring。
 */
class ResumePlannerTest {

    private static final List<ChatMessage> BASE = List.of(
            SystemMessage.from("SYS"), UserMessage.from("帮我查年假政策"));

    @Test
    void 完整轮被重放_形态与顺序正确() {
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, llmJson(null, "call_1", "searchKnowledge", "{\"query\":\"年假\"}")),
                tool(1, 2, AgentStepStatusEnum.SUCCEEDED, "call_1", "searchKnowledge", "{\"hits\":[]}", 0));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(1, r.roundsCompleted());
        assertEquals(2, r.maxSeq());
        assertEquals(List.of(2L), r.replayedStepIds());
        assertFalse(r.danglingSideEffect());
        // base(2) + AiMessage + ToolExecutionResultMessage
        assertEquals(4, r.messages().size());
        assertEquals(2, r.messages().size() - BASE.size());
        AiMessage ai = assertInstanceOf(AiMessage.class, r.messages().get(2));
        assertEquals(1, ai.toolExecutionRequests().size());
        assertEquals("call_1", ai.toolExecutionRequests().get(0).id());
        assertInstanceOf(ToolExecutionResultMessage.class, r.messages().get(3));
    }

    @Test
    void 中断的LLM轮整轮丢弃_不重放() {
        // 第 1 轮完整；第 2 轮的 LLM 步被中断
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, llmJson(null, "call_1", "ping", "{}")),
                tool(1, 2, AgentStepStatusEnum.SUCCEEDED, "call_1", "ping", "pong", 0),
                llm(2, 3, AgentStepStatusEnum.INTERRUPTED, llmJson("半截文字", null, null, null)));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(1, r.roundsCompleted());
        // maxSeq 取【全部】步骤（含那条 INTERRUPTED 的 seq=3），而不是只算已重放的——否则续跑会撞唯一键
        assertEquals(3, r.maxSeq());
        assertEquals(BASE.size() + 2, r.messages().size());
        assertFalse(r.danglingSideEffect());
    }

    @Test
    void LLM步成功但工具无结果_整轮丢弃_不产生孤儿tool_call() {
        // 这是最危险的形态：若重放了 AiMessage 却不带 tool_result，OpenAI 兼容端点会 400
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, llmJson(null, "call_1", "ping", "{}")),
                tool(1, 2, AgentStepStatusEnum.PENDING, "call_1", "ping", null, 0));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(0, r.roundsCompleted());
        assertEquals(BASE.size(), r.messages().size(), "整轮丢弃后不应多出任何消息");
        assertFalse(r.danglingSideEffect(), "只读工具没结果不算副作用悬挂");
    }

    @Test
    void 失败的工具有结果_可重放() {
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, llmJson(null, "call_1", "ping", "{}")),
                tool(1, 2, AgentStepStatusEnum.FAILED, "call_1", "ping", "工具执行失败：超时", 0));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(1, r.roundsCompleted());
        assertEquals(BASE.size() + 2, r.messages().size());
    }

    @Test
    void LLM请求了两个工具_只落了一个步骤_整轮丢弃() {
        // M10：人工确认可能停在批次中间；不能因为「已存在的步骤都有结果」就误判整轮完整
        String response = "{\"text\":null,\"toolCalls\":["
                + "{\"id\":\"call_1\",\"name\":\"a\",\"arguments\":\"{}\"},"
                + "{\"id\":\"call_2\",\"name\":\"b\",\"arguments\":\"{}\"}]}";
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, response),
                tool(1, 2, AgentStepStatusEnum.SUCCEEDED, "call_1", "a", "ok", 0));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(0, r.roundsCompleted(), "缺失 call_2 的结果，整轮不完整");
        assertEquals(BASE.size(), r.messages().size(), "不能重放出孤儿 tool_call");
        assertFalse(r.danglingSideEffect());
    }

    @Test
    void 同一轮残留旧工具步_只重放当前响应请求过的结果() {
        // 崩溃后同一 stepNo 重新生成：旧尝试的 call_1 没结果，新响应的 call_2 有结果
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, llmJson(null, "call_2", "ping", "{}")),
                tool(1, 2, AgentStepStatusEnum.FAILED, "call_1", "oldTool", null, 0),
                tool(1, 3, AgentStepStatusEnum.SUCCEEDED, "call_2", "ping", "pong", 0));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(1, r.roundsCompleted());
        assertEquals(List.of(3L), r.replayedStepIds(), "旧工具步不能被当成结果重放");
        assertEquals(BASE.size() + 2, r.messages().size());
    }

    @Test
    void 副作用步无结果_标记为需人工确认() {
        List<AgentStep> steps = List.of(
                llm(1, 1, AgentStepStatusEnum.SUCCEEDED, llmJson(null, "call_1", "createTicket", "{}")),
                tool(1, 2, AgentStepStatusEnum.PENDING, "call_1", "createTicket", null, 1));

        ResumePlanner.Result r = ResumePlanner.reconstruct(BASE, steps);

        assertEquals(0, r.roundsCompleted());
        assertTrue(r.danglingSideEffect(), "副作用步无结果必须转人工确认");
    }

    // ---------------- 辅助 ----------------

    private static AgentStep llm(int stepNo, int seq, AgentStepStatusEnum status, String responseJson) {
        AgentStep s = new AgentStep();
        s.setId((long) seq);
        s.setStepNo(stepNo);
        s.setSeq(seq);
        s.setType(AgentStepTypeEnum.LLM_CALL.name());
        s.setStatus(status.name());
        s.setResponseJson(responseJson);
        return s;
    }

    private static AgentStep tool(int stepNo, int seq, AgentStepStatusEnum status, String toolCallId,
                                  String toolName, String result, int sideEffect) {
        AgentStep s = new AgentStep();
        s.setId((long) seq);
        s.setStepNo(stepNo);
        s.setSeq(seq);
        s.setType(AgentStepTypeEnum.TOOL_CALL.name());
        s.setStatus(status.name());
        s.setToolCallId(toolCallId);
        s.setToolName(toolName);
        s.setToolResultJson(result);
        s.setSideEffect(sideEffect);
        return s;
    }

    private static String llmJson(String text, String id, String name, String arguments) {
        String textPart = text == null ? "null" : "\"" + text + "\"";
        if (id == null) {
            return "{\"text\":" + textPart + "}";
        }
        return "{\"text\":" + textPart + ",\"toolCalls\":[{\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"arguments\":" + args(arguments) + "}]}";
    }

    private static String args(String arguments) {
        return arguments == null ? "null" : "\"" + arguments.replace("\"", "\\\"") + "\"";
    }
}
