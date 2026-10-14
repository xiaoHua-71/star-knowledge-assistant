package com.xiaohua.star.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentEventTypeEnum;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * ReAct 主循环（**落库版**，M8）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p>相比 M7 的内存循环，这里把每一步都写进 MySQL（`agent_run` / `agent_step` / `agent_event`）：
 * 开始建 run → 每轮落 LLM 步骤 → 每个工具**先落意图行再执行**、执行后回填结果 → 结束落助手消息、
 * 收尾 run。每个步骤一个短事务（{@link AgentStateService}），<b>事务绝不跨越模型/工具调用</b>。
 *
 * <p><b>循环仍是阻塞式</b>（把 LC4j 的流式回调桥接成阻塞等待），必须跑在专用线程池上（{@code agentExecutor}）。
 *
 * <p><b>M8 不做</b>：中断、续跑、重连、幂等键、人工确认（属 M9/M10）。安全点位置已按 knowledge/12 摆好，
 * 但当前不检查中断标志。客户端断开后循环仍会跑完并落库（只有推流被 sink 跳过）——取消逻辑属 M9。
 */
@Slf4j
@Component
public class AgentRunner {

    /** 撞到护栏、且模型没留下任何文字时的兜底回答。 */
    static final String MAX_ROUNDS_MESSAGE =
            "（已达到最大工具调用轮次，未能得出结论。请把问题拆解得更具体，或稍后重试。）";

    /** 仅用于把事件体序列化进 agent_event、以及解析工具参数/结果；不需要全局的 Long→String 配置。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AgentToolRegistry toolRegistry;

    @Resource
    private AgentProperties agentProperties;

    @Resource
    private AgentStateService agentStateService;

    @Resource
    private ConversationMessageService conversationMessageService;

    /** 一次运行的结果。 */
    public record RunResult(long runId, String finalText, Long assistantMessageId,
                            int rounds, boolean maxRoundsHit) {
    }

    /**
     * 跑一次落库循环。
     *
     * @param model          流式对话模型（由调用方解析好并保证非 null）
     * @param ctx            运行上下文：userId / conversationId / kbIds
     * @param inputMessageId 触发本次运行的用户消息 id（写入 run 与 run_start 事件）
     * @param prompt         已重建好的初始消息（系统提示 + 预算内历史，末条是当前用户消息）
     * @param sink           事件出口
     */
    public RunResult run(StreamingChatModel model, RunContext ctx, Long inputMessageId,
                         List<ChatMessage> prompt, AgentEventSink sink) {
        List<ToolSpecification> specs = toolRegistry.specifications();
        int maxRounds = agentProperties.getMaxRounds();
        long startMs = System.currentTimeMillis();

        long runId = agentStateService.createRun(ctx.conversationId(), ctx.userId(), inputMessageId, maxRounds);
        emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.RUN_START,
                Map.of("runId", str(runId),
                        "conversationId", str(ctx.conversationId()),
                        "messageId", str(inputMessageId)));
        agentStateService.markRunning(runId);

        List<ChatMessage> messages = new ArrayList<>(prompt);
        int totalIn = 0;
        int totalOut = 0;
        int round = 0;
        int seq = 0;
        String lastText = "";

        try {
            while (round < maxRounds) {
                round++;
                seq++;
                long llmStart = System.currentTimeMillis();
                long llmStepId = agentStateService.beginLlmStep(
                        runId, round, seq, llmRequestSnapshot(messages, specs));

                ChatResponse response;
                try {
                    response = streamOnce(model,
                            ChatRequest.builder().messages(messages).toolSpecifications(specs).build(), sink);
                } catch (RuntimeException e) {
                    agentStateService.finishLlmStep(llmStepId, null,
                            System.currentTimeMillis() - llmStart, AgentStepStatusEnum.FAILED, e.getMessage());
                    throw e;
                }

                AiMessage ai = response.aiMessage();
                int[] tok = tokenCountsOf(response);
                totalIn += tok[0];
                totalOut += tok[1];
                agentStateService.finishLlmStep(llmStepId, llmResponseSnapshot(ai),
                        System.currentTimeMillis() - llmStart, AgentStepStatusEnum.SUCCEEDED, null);

                String text = ai.text();
                if (text != null && !text.isBlank()) {
                    lastText = text;
                }
                messages.add(ai);

                if (!ai.hasToolExecutionRequests()) {
                    return finish(runId, ctx, text == null ? "" : text, totalIn, totalOut, round, startMs, false, sink);
                }

                // 逐个执行工具。安全点 2 的位置在「执行前」——先把意图行提交，再执行（knowledge/12 坑 1）
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    seq++;
                    boolean sideEffect = toolRegistry.sideEffect(req.name());
                    long toolStepId = agentStateService.beginToolStep(runId, round, seq,
                            req.id(), req.name(), req.arguments(), sideEffect, false);

                    String stepId = str(toolStepId);
                    String toolCallId = req.id() != null && !req.id().isBlank() ? req.id() : "step-" + seq;
                    emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.TOOL_CALL,
                            toolCallData(stepId, toolCallId, req.name(), req.arguments(), sideEffect));

                    ToolRunResult result = toolRegistry.execute(req, ctx);
                    String resultText = truncate(result.text());
                    boolean truncated = !resultText.equals(result.text());
                    agentStateService.finishToolStep(toolStepId, resultText, result.elapsedMs(),
                            result.isError() ? AgentStepStatusEnum.FAILED : AgentStepStatusEnum.SUCCEEDED,
                            result.isError() ? resultText : null);
                    emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.TOOL_RESULT,
                            toolResultData(stepId, toolCallId, !result.isError(), result.elapsedMs(), resultText, truncated));

                    messages.add(ToolExecutionResultMessage.from(req, resultText));
                }
            }

            log.warn("Agent 循环撞到 maxRounds: runId={}, maxRounds={}", runId, maxRounds);
            String answer = lastText.isBlank() ? MAX_ROUNDS_MESSAGE : lastText;
            return finish(runId, ctx, answer, totalIn, totalOut, round, startMs, true, sink);

        } catch (Exception e) {
            log.warn("Agent 运行失败: runId={}", runId, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            agentStateService.failRun(runId, msg);
            emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.ERROR,
                    Map.of("code", 50001, "message", "模型调用失败：" + msg,
                            "runId", str(runId), "retryable", false));
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    /** 正常收尾：落助手消息 → 收尾 run → 发 run_end 事件。 */
    private RunResult finish(long runId, RunContext ctx, String answer, int totalIn, int totalOut,
                             int rounds, long startMs, boolean maxRoundsHit, AgentEventSink sink) {
        Integer tokens = (totalIn + totalOut) == 0 ? null : (totalIn + totalOut);
        ConversationMessage assistant = conversationMessageService.appendMessage(
                ctx.conversationId(), ctx.userId(),
                ConversationMessage.ROLE_ASSISTANT, answer, tokens, runId);
        agentStateService.finalizeRun(runId, assistant.getId(), totalIn, totalOut, rounds);

        emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.RUN_END,
                Map.of("runId", str(runId), "status", "COMPLETED",
                        "messageId", str(assistant.getId()),
                        "elapsedMs", (int) (System.currentTimeMillis() - startMs),
                        "rounds", rounds));
        return new RunResult(runId, answer, assistant.getId(), rounds, maxRoundsHit);
    }

    /** 落库一个里程碑事件（拿自增 id）并推给 sink。返回事件 id。 */
    private long emit(AgentEventSink sink, long runId, Long conversationId,
                      AgentEventTypeEnum type, Map<String, Object> data) {
        long eventId = agentStateService.appendEvent(runId, conversationId, type, toJson(data));
        sink.onEvent(eventId, type.code(), data);
        return eventId;
    }

    /** 把一次流式调用桥接成阻塞：回调里推 delta、结束时完成 future。 */
    private ChatResponse streamOnce(StreamingChatModel model, ChatRequest request, AgentEventSink sink) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    sink.onDelta(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("对话被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("模型调用失败：" + cause.getMessage(), cause);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = agentProperties.getMaxToolResultChars();
        if (max > 0 && text.length() > max) {
            return text.substring(0, max) + "…（结果已截断）";
        }
        return text;
    }

    // ---------------- 事件体（id 一律转成字符串，与前端契约一致） ----------------

    private static Map<String, Object> toolCallData(String stepId, String toolCallId, String toolName,
                                                    String argumentsJson, boolean sideEffect) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stepId", stepId);
        d.put("toolCallId", toolCallId);
        d.put("toolName", toolName);
        d.put("arguments", parseToObject(argumentsJson, Map.of()));
        d.put("sideEffect", sideEffect);
        return d;
    }

    private static Map<String, Object> toolResultData(String stepId, String toolCallId, boolean success,
                                                      long elapsedMs, String resultText, boolean truncated) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stepId", stepId);
        d.put("toolCallId", toolCallId);
        d.put("success", success);
        d.put("elapsedMs", (int) elapsedMs);
        d.put("result", parseToObject(resultText, Map.of("text", resultText == null ? "" : resultText)));
        d.put("truncated", truncated);
        return d;
    }

    // ---------------- 快照（不序列化 LC4j 消息对象，避免体积与兼容问题） ----------------

    private static String llmRequestSnapshot(List<ChatMessage> messages, List<ToolSpecification> specs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageCount", messages.size());
        m.put("toolNames", specs.stream().map(ToolSpecification::name).toList());
        return toJson(m);
    }

    private static String llmResponseSnapshot(AiMessage ai) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", ai.text());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", r.id());
            c.put("name", r.name());
            c.put("arguments", r.arguments());
            calls.add(c);
        }
        if (!calls.isEmpty()) {
            m.put("toolCalls", calls);
        }
        return toJson(m);
    }

    /** 返回 {@code [input, output]}；两者都缺时把 total 记到 output，至少不丢总量。 */
    private static int[] tokenCountsOf(ChatResponse response) {
        if (response.metadata() == null || response.metadata().tokenUsage() == null) {
            return new int[]{0, 0};
        }
        var u = response.metadata().tokenUsage();
        int in = u.inputTokenCount() == null ? 0 : u.inputTokenCount();
        int out = u.outputTokenCount() == null ? 0 : u.outputTokenCount();
        if (in == 0 && out == 0 && u.totalTokenCount() != null) {
            out = u.totalTokenCount();
        }
        return new int[]{in, out};
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 解析成对象放进事件；失败回退。工具参数/结果格式不可全信，坏了也不能拖垮运行。 */
    private static Object parseToObject(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
