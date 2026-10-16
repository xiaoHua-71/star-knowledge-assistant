package com.xiaohua.star.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentEventTypeEnum;
import com.xiaohua.star.model.enums.AgentRunStatusEnum;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ReAct 主循环（**落库版 + 可中断可续跑**，M8/M9）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p>相比 M8，M9 加了「安全点检查」与「续跑」：
 * <ul>
 *   <li><b>安全点 1</b>（每轮开头、模型调用前）与 <b>安全点 2</b>（模型返回后）各查一次 Redis 中断标志；
 *       任一命中即<b>挂起</b>（{@code RUNNING → SUSPENDED}，发 {@code interrupt} 事件），<b>不写助手消息</b>。
 *       注意 SP2 <b>无条件</b>检查——哪怕这轮是「最终回答」也挂起（见 knowledge/12 ⑦ 的取舍说明）。</li>
 *   <li>挂起用 <b>return</b> 而非抛异常（抛异常会被下面的 {@code catch} 误判成 FAILED）。</li>
 *   <li>{@link #resume} 从调用方重建好的消息列表接着跑，轮次/序号/token 累计都续上。</li>
 * </ul>
 *
 * <p><b>循环仍是阻塞式</b>——必须跑在专用线程池 {@code agentExecutor} 上（一运行一线程）。
 * 客户端断开后循环仍会跑完或挂起（只有推流被 hub 跳过），挂起靠中断标志收敛。
 */
@Slf4j
@Component
public class AgentRunner {

    /** 撞到护栏、且模型没留下任何文字时的兜底回答。 */
    static final String MAX_ROUNDS_MESSAGE =
            "（已达到最大工具调用轮次，未能得出结论。请把问题拆解得更具体，或稍后重试。）";

    /** 流式中断标志的轮询间隔（ms）：逐 token 查 Redis 太贵，节流到这个间隔。 */
    private static final long INTERRUPT_POLL_MS = 200L;

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

    @Resource
    private RunInterruptFlag interruptFlag;

    /**
     * 一次运行的结果。{@code suspended} 为 true 时表示已挂起（无助手消息、无 run_end）。
     */
    public record RunResult(long runId, String finalText, Long assistantMessageId,
                            int rounds, boolean maxRoundsHit, boolean suspended) {
    }

    /**
     * 跑一次新的落库循环（run 已由调用方创建）。
     *
     * @param runId          已创建的运行 id（{@code agent_run.id}）
     * @param inputMessageId 触发本次运行的用户消息 id
     * @param prompt         已重建好的初始消息（系统提示 + 预算内历史，末条是当前用户消息）
     */
    public RunResult run(StreamingChatModel model, RunContext ctx, long runId, Long inputMessageId,
                         List<ChatMessage> prompt, AgentEventSink sink) {
        emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.RUN_START,
                Map.of("runId", str(runId),
                        "conversationId", str(ctx.conversationId()),
                        "messageId", str(inputMessageId)));
        agentStateService.markRunning(runId);
        return drive(model, ctx, runId, prompt, 0, 0, 0, 0, sink);
    }

    /**
     * 从挂起点续跑（run 已由调用方 {@code resumeRun} 翻转为 RUNNING）。
     *
     * @param messages 由 {@link ResumePlanner} 重建好的消息列表（基础历史 + 可重放的完整轮）
     * @param round0   已完成轮数（续跑从下一轮开始）
     * @param seq0     已重放步骤的最大 seq（续跑继续计号的起点）
     * @param in0/out0 运行已累计的 token（续跑接着累加，不重置）
     */
    public RunResult resume(StreamingChatModel model, RunContext ctx, long runId, Long inputMessageId,
                            List<ChatMessage> messages, int round0, int seq0, int in0, int out0,
                            AgentEventSink sink) {
        emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.RUN_START,
                Map.of("runId", str(runId),
                        "conversationId", str(ctx.conversationId()),
                        "messageId", str(inputMessageId)));
        return drive(model, ctx, runId, messages, round0, seq0, in0, out0, sink);
    }

    /** 共享主循环（{@link #run} 与 {@link #resume} 都走这里）。 */
    private RunResult drive(StreamingChatModel model, RunContext ctx, long runId, List<ChatMessage> prompt,
                            int round0, int seq0, int in0, int out0, AgentEventSink sink) {
        List<ToolSpecification> specs = toolRegistry.specifications();
        int maxRounds = agentProperties.getMaxRounds();
        long startMs = System.currentTimeMillis();

        List<ChatMessage> messages = new ArrayList<>(prompt);
        int totalIn = in0;
        int totalOut = out0;
        int round = round0;
        int seq = seq0;
        String lastText = "";

        try {
            while (round < maxRounds) {
                // 安全点 1：轮首（模型调用前）。命中则挂起，此时无在飞步骤、状态最干净
                Optional<InterruptTypeEnum> sp1 = interruptFlag.get(runId);
                if (sp1.isPresent()) {
                    return suspend(runId, ctx, sp1.get(), sink);
                }

                round++;
                seq++;
                long llmStart = System.currentTimeMillis();
                long llmStepId = agentStateService.beginLlmStep(
                        runId, round, seq, llmRequestSnapshot(messages, specs));

                ChatResponse response;
                try {
                    response = streamOnce(model,
                            ChatRequest.builder().messages(messages).toolSpecifications(specs).build(), sink, runId);
                } catch (RuntimeException e) {
                    agentStateService.finishLlmStep(llmStepId, null,
                            System.currentTimeMillis() - llmStart, AgentStepStatusEnum.FAILED, e.getMessage());
                    throw e;
                }

                AiMessage ai = normalizeToolCallIds(response.aiMessage(), round);
                int[] tok = tokenCountsOf(response);
                totalIn += tok[0];
                totalOut += tok[1];

                // 安全点 2：模型返回后（无条件）。命中则把本步记 INTERRUPTED 并挂起——本轮的输出（无论
                // 是半截文字还是完整回答）都丢弃，续跑时重生成这一段。见 knowledge/12 ⑦ 的取舍。
                Optional<InterruptTypeEnum> sp2 = interruptFlag.get(runId);
                if (sp2.isPresent()) {
                    agentStateService.finishLlmStep(llmStepId, llmResponseSnapshot(ai),
                            System.currentTimeMillis() - llmStart, AgentStepStatusEnum.INTERRUPTED, null);
                    return suspend(runId, ctx, sp2.get(), sink);
                }

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
            interruptFlag.clear(runId);
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
        interruptFlag.clear(runId);

        emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.RUN_END,
                Map.of("runId", str(runId), "status", "COMPLETED",
                        "messageId", str(assistant.getId()),
                        "elapsedMs", (int) (System.currentTimeMillis() - startMs),
                        "rounds", rounds));
        return new RunResult(runId, answer, assistant.getId(), rounds, maxRoundsHit, false);
    }

    /** 挂起：RUNNING → SUSPENDED + interruptType，清中断标志，发 {@code interrupt} 事件。**不写助手消息**（坑 5）。 */
    private RunResult suspend(long runId, RunContext ctx, InterruptTypeEnum type, AgentEventSink sink) {
        agentStateService.suspendRun(runId, type);
        interruptFlag.clear(runId);
        emit(sink, runId, ctx.conversationId(), AgentEventTypeEnum.INTERRUPT,
                Map.of("runId", str(runId),
                        "status", AgentRunStatusEnum.SUSPENDED.name(),
                        "interruptType", type.name()));
        log.info("Agent 运行已挂起: runId={}, interruptType={}", runId, type);
        return new RunResult(runId, "", null, 0, false, true);
    }

    /** 落库一个里程碑事件（拿自增 id）并推给 sink。返回事件 id。 */
    private long emit(AgentEventSink sink, long runId, Long conversationId,
                      AgentEventTypeEnum type, Map<String, Object> data) {
        long eventId = agentStateService.appendEvent(runId, conversationId, type, toJson(data));
        sink.onEvent(eventId, type.code(), data);
        return eventId;
    }

    /**
     * 把一次流式调用桥接成阻塞：回调里推 delta、结束时完成 future。
     *
     * <p><b>中断标志置位后不再推 delta</b>（立刻停输出，体验上的「停止」），但<b>不提前掐断 HTTP</b>——
     * LC4j 的 {@code streamingHandle().cancel()} 之后终态回调是否仍到达依 provider 而定，会让我们阻塞等待的
     * future 挂死。所以线程等这次调用自然结束，再由安全点 2 收敛（最多一次调用的延迟，符合 knowledge/12）。
     */
    private ChatResponse streamOnce(StreamingChatModel model, ChatRequest request, AgentEventSink sink, long runId) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        AtomicLong lastCheck = new AtomicLong(0L);
        AtomicBoolean suppressed = new AtomicBoolean(false);
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse == null || partialResponse.isEmpty()) {
                    return;
                }
                if (!suppressed.get()) {
                    long now = System.currentTimeMillis();
                    long last = lastCheck.get();
                    if (now - last >= INTERRUPT_POLL_MS && lastCheck.compareAndSet(last, now)
                            && interruptFlag.get(runId).isPresent()) {
                        suppressed.set(true);
                    }
                }
                if (suppressed.get()) {
                    return;
                }
                sink.onDelta(partialResponse);
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

    /**
     * 把模型返回的工具调用 id 规整成非空（模型偶尔不给 id；测试替身也不给）。
     * <b>必须规整</b>：响应快照里存的 id、工具步的 {@code toolCallId}、以及回喂给模型的消息必须三处一致，
     * 否则续跑重放时会拼出「有 tool_call 没有 tool_result」的孤儿请求。
     */
    private static AiMessage normalizeToolCallIds(AiMessage ai, int round) {
        List<ToolExecutionRequest> reqs = ai.toolExecutionRequests();
        if (reqs.isEmpty()) {
            return ai;
        }
        List<ToolExecutionRequest> normalized = new ArrayList<>(reqs.size());
        for (int i = 0; i < reqs.size(); i++) {
            ToolExecutionRequest r = reqs.get(i);
            String id = r.id() == null || r.id().isBlank() ? "call-" + round + "-" + (i + 1) : r.id();
            normalized.add(ToolExecutionRequest.builder()
                    .id(id).name(r.name()).arguments(r.arguments()).build());
        }
        return AiMessage.from(ai.text(), normalized);
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
