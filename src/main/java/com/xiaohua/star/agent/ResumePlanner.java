package com.xiaohua.star.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 续跑时重建消息列表（纯逻辑，不依赖 Spring，可直接单测）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p><b>核心规则：只重放「完整轮」。</b>一轮完整 ⇔ 该轮 LLM 步 {@code SUCCEEDED} 且其全部工具步都有结果
 * （{@code SUCCEEDED}，或 {@code FAILED}——失败结果也要回给模型）。遇到第一个不完整轮就<b>整轮丢弃</b>、
 * 由主循环重新生成。
 *
 * <p><b>为什么必须整轮丢、不能只丢半截？</b> OpenAI 兼容协议不允许出现「有 tool_call 却没有对应
 * tool_result」的 {@code AiMessage}——那是一次 400（孤儿 tool_call）。所以一旦某轮的工具结果不齐，
 * 连它前面的 {@code AiMessage} 也不能重放。
 *
 * <p>基础历史（系统消息 + 预算内 USER/ASSISTANT）由调用方用 {@code MemoryBuilder} 备好传入；
 * 本类只在此之上叠加「本次运行已完成的工具/LLM 步」。
 */
public final class ResumePlanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResumePlanner() {
    }

    /**
     * @param messages           重建好的完整消息列表：基础历史 + 可重放的完整轮（可直接喂回循环）
     * @param roundsCompleted    已重放的完整轮数（续跑循环的起始轮次）
     * @param maxSeq             <b>全部步骤</b>（含未完成的那条）的最大 {@code seq}——续跑新步骤的计号起点。
     *                           必须含未完成步骤：否则「模型返回后挂起」时那条 {@code INTERRUPTED} 的 LLM 步
     *                           已占了 seq=3，续跑新 LLM 步再用 seq=3 会撞 {@code uk_runId_seq}
     * @param replayedStepIds    被重放的工具步 id（调用方据此打 {@code replayed=1}）
     * @param danglingSideEffect 是否存在「已开始、结果未知」的副作用步——存在则<b>不能自动续跑</b>，转人工确认（M10）
     */
    public record Result(List<ChatMessage> messages, int roundsCompleted, int maxSeq,
                         List<Long> replayedStepIds, boolean danglingSideEffect) {
    }

    public static Result reconstruct(List<ChatMessage> base, List<AgentStep> steps) {
        List<ChatMessage> messages = new ArrayList<>(base);
        List<Long> replayed = new ArrayList<>();
        int roundsCompleted = 0;
        int maxSeq = 0;
        for (AgentStep s : steps) {
            if (s.getSeq() != null) {
                maxSeq = Math.max(maxSeq, s.getSeq());
            }
        }
        boolean danglingSideEffect = false;

        for (Map.Entry<Integer, List<AgentStep>> e : groupByRound(steps).entrySet()) {
            Round r = new Round(e.getValue());
            if (!r.isComplete()) {
                danglingSideEffect = r.danglingSideEffect();
                break;
            }
            if (!r.toolRequests().isEmpty()) {
                messages.add(AiMessage.from(r.text(), r.toolRequests()));
            } else if (r.text() != null && !r.text().isBlank()) {
                messages.add(AiMessage.from(r.text()));
            }
            for (AgentStep t : r.tools) {
                messages.add(ToolExecutionResultMessage.from(t.getToolCallId(), t.getToolName(), t.getToolResultJson()));
                replayed.add(t.getId());
            }
            roundsCompleted++;
        }
        return new Result(messages, roundsCompleted, maxSeq, replayed, danglingSideEffect);
    }

    /** 按 {@code stepNo} 分组；{@code LinkedHashMap} 保持轮次升序（steps 已按 seq 升序）。 */
    private static LinkedHashMap<Integer, List<AgentStep>> groupByRound(List<AgentStep> steps) {
        LinkedHashMap<Integer, List<AgentStep>> map = new LinkedHashMap<>();
        for (AgentStep s : steps) {
            map.computeIfAbsent(s.getStepNo(), k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    /** 一轮的切片视图。 */
    private static final class Round {
        final AgentStep llm;
        final List<AgentStep> tools = new ArrayList<>();

        Round(List<AgentStep> steps) {
            AgentStep llmStep = null;
            for (AgentStep s : steps) {
                if (AgentStepTypeEnum.LLM_CALL.name().equals(s.getType())) {
                    llmStep = s;
                } else if (AgentStepTypeEnum.TOOL_CALL.name().equals(s.getType())) {
                    tools.add(s);
                }
            }
            this.llm = llmStep;
        }

        boolean isComplete() {
            if (llm == null || !AgentStepStatusEnum.SUCCEEDED.name().equals(llm.getStatus())) {
                return false;
            }
            return tools.stream().allMatch(t -> t.getToolResultJson() != null);
        }

        /** 已开始但没结果的副作用步（read-only 的可以靠重生成轮次自愈，副作用的不行）。 */
        boolean danglingSideEffect() {
            return tools.stream().anyMatch(t -> isSideEffect(t) && t.getToolResultJson() == null);
        }

        String text() {
            return textOf(llm.getResponseJson());
        }

        List<ToolExecutionRequest> toolRequests() {
            return toolRequestsOf(llm.getResponseJson());
        }
    }

    private static boolean isSideEffect(AgentStep s) {
        return s.getSideEffect() != null && s.getSideEffect() == 1;
    }

    /** 从 LLM 步的 {@code responseJson}（{@code {text, toolCalls:[{id,name,arguments}]}}）取文本。 */
    public static String textOf(String responseJson) {
        try {
            JsonNode node = MAPPER.readTree(responseJson);
            JsonNode text = node.get("text");
            return text == null || text.isNull() ? null : text.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 LLM 步的 {@code responseJson} 还原工具调用请求（id/name/arguments 三者必须齐全）。 */
    static List<ToolExecutionRequest> toolRequestsOf(String responseJson) {
        List<ToolExecutionRequest> reqs = new ArrayList<>();
        try {
            JsonNode calls = MAPPER.readTree(responseJson).get("toolCalls");
            if (calls == null || !calls.isArray()) {
                return reqs;
            }
            for (JsonNode c : calls) {
                reqs.add(ToolExecutionRequest.builder()
                        .id(c.path("id").asText(null))
                        .name(c.path("name").asText(null))
                        .arguments(c.path("arguments").asText(null))
                        .build());
            }
        } catch (Exception e) {
            return List.of();
        }
        return reqs;
    }
}
