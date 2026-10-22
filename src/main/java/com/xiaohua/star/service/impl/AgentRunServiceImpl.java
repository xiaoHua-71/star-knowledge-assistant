package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.agent.AgentEventSink;
import com.xiaohua.star.agent.AgentRunner;
import com.xiaohua.star.agent.AgentStreamHub;
import com.xiaohua.star.agent.AgentToolRegistry;
import com.xiaohua.star.agent.ResumePlanner;
import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.agent.RunInterruptFlag;
import com.xiaohua.star.agent.SseHeartbeat;
import com.xiaohua.star.ai.MemoryBuilder;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.AgentExecutorConfig;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.entity.AgentEvent;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentEventTypeEnum;
import com.xiaohua.star.model.enums.AgentRunStatusEnum;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
import com.xiaohua.star.model.vo.AgentRunVO;
import com.xiaohua.star.model.vo.AgentStepVO;
import com.xiaohua.star.model.vo.PendingToolVO;
import com.xiaohua.star.service.AgentRunService;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
import com.xiaohua.star.service.ObservabilityService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Agent 运行操作实现（M9 中断/续跑/重连 + M10 人工确认）。见 knowledge/12、frontend/40。
 *
 * <p>中断 = 只能「登记请求」（写 Redis 标志），真正的挂起由运行线程在下个安全点完成——
 * 所以接口返回成功 ≠ 已经停了（frontend/40 反复强调这点）。
 *
 * <p><b>人工确认（M10）</b>：运行停在 {@code WAITING_APPROVAL}，用户 approve 后先执行待确认工具
 * （幂等认领，恰好一次），再把同批次剩余调用补齐，最后复用 {@code AgentRunner.resume} 从断点继续。
 * 拒绝/停止则把本批次未执行的调用全部写成「未执行」结果，保证整轮可重放（不会丢拒绝原因）。
 */
@Slf4j
@Service
public class AgentRunServiceImpl implements AgentRunService {

    /** 与 /chat/stream 一致：SSE 超时必须显式给，否则退回容器默认 ~30s 会掐断长回答。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AgentStateService agentStateService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ConversationMessageService conversationMessageService;

    @Resource
    private MemoryBuilder memoryBuilder;

    @Resource
    private AgentRunner agentRunner;

    @Resource
    private AgentStreamHub agentStreamHub;

    @Resource
    private SseHeartbeat sseHeartbeat;

    @Resource
    private RunInterruptFlag interruptFlag;

    @Resource
    private AgentToolRegistry toolRegistry;

    @Resource
    private ObservabilityService observabilityService;

    @Resource
    private ObjectProvider<StreamingChatModel> streamingChatModelProvider;

    @Resource(name = AgentExecutorConfig.AGENT_EXECUTOR)
    private Executor agentExecutor;

    @Override
    public boolean interrupt(long runId, Long userId) {
        AgentRun run = requireOwned(runId, userId);
        String status = run.getStatus();

        if (AgentRunStatusEnum.WAITING_APPROVAL.name().equals(status)) {
            // 等确认的运行没有循环线程可收敛，直接在这里把本批次未执行的调用封掉并挂起
            AgentStep pending = agentStateService.findPendingApprovalStep(runId);
            if (pending != null) {
                fillUnresolvedBatch(run, pending, "用户停止，该操作未执行。");
            }
            agentStateService.suspendRun(runId, InterruptTypeEnum.USER_STOP);
            appendInterruptEvent(runId, run.getConversationId(),
                    AgentRunStatusEnum.SUSPENDED.name(), InterruptTypeEnum.USER_STOP.name(), null);
            return true;
        }

        if (AgentRunStatusEnum.PENDING.name().equals(status) || AgentRunStatusEnum.RUNNING.name().equals(status)) {
            interruptFlag.set(runId, InterruptTypeEnum.USER_STOP);
        }
        // 已挂起 / 已终态：停止的目标状态本就是「不在跑」，视为已达成，幂等返回 true
        return true;
    }

    @Override
    public AgentRunVO resume(long runId, Long userId) {
        AgentRun run = requireOwned(runId, userId);
        if (!AgentRunStatusEnum.SUSPENDED.name().equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "只能继续已挂起的运行（当前 " + run.getStatus() + "）");
        }
        StreamingChatModel model = streamingChatModelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "对话模型未配置（缺少 ai.chat.api-key），无法继续");
        }
        Conversation conversation = conversationService.getById(run.getConversationId());
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        requireInputMessageIsLatest(run);

        List<ChatMessage> base = memoryBuilder.build(run.getConversationId(), ChatServiceImpl.SYSTEM_PROMPT);
        List<AgentStep> steps = agentStateService.listSteps(runId);
        ResumePlanner.Result plan = ResumePlanner.reconstruct(base, steps);
        if (plan.danglingSideEffect()) {
            // M10：不抛死。转成人工确认：
            //   - 从没认领过（无幂等键）→ 用户同意后可以安全执行
            //   - 已认领但结果未知 → 用户「同意」只代表已知晓，系统绝不重跑
            AgentStep dangling = findDanglingSideEffectStep(runId);
            int round = dangling == null || dangling.getStepNo() == null ? 0 : dangling.getStepNo();
            if (dangling == null || !agentStateService.markWaitingApproval(runId, round)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "该运行存在结果未知的副作用操作，且状态已变化，无法转人工确认");
            }
            String warning = dangling.getIdempotencyKey() == null
                    ? null : AgentRunner.UNKNOWN_SIDE_EFFECT_WARNING;
            appendApprovalInterruptEvent(runId, run.getConversationId(), dangling, warning);
            return toVO(agentStateService.getRun(runId));
        }

        // 条件翻转 SUSPENDED → RUNNING：false 说明已被别的请求续跑，别重复起循环
        if (!agentStateService.resumeRun(runId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行状态已变化，无法继续");
        }
        logReplayedSteps(run, steps, plan.replayedStepIds(), null);

        RunContext ctx = new RunContext(run.getUserId(), run.getConversationId(),
                parseKbIds(conversation.getKbIds()));
        // 先建通道（此刻还没有 emitter，delta 照常累积，等前端接流时给快照），再提交循环
        agentStreamHub.create(runId, null);
        AgentEventSink sink = agentStreamHub.sink(runId);
        agentExecutor.execute(() -> {
            try {
                agentRunner.resume(model, ctx, runId, run.getInputMessageId(), plan.messages(),
                        plan.roundsCompleted(), plan.maxSeq(), run.getTokenIn(), run.getTokenOut(), sink);
            } catch (Exception e) {
                log.warn("Agent 续跑失败: runId={}", runId, e);
            } finally {
                agentStreamHub.finish(runId);
            }
        });
        return toVO(agentStateService.getRun(runId));
    }

    @Override
    public AgentRunVO approve(long runId, Long userId, boolean approve, String reason) {
        AgentRun run = requireOwned(runId, userId);
        if (!AgentRunStatusEnum.WAITING_APPROVAL.name().equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "当前状态不是等待确认（" + run.getStatus() + "）");
        }
        AgentStep pending = agentStateService.findPendingApprovalStep(runId);
        if (pending == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有待确认的工具调用");
        }

        if (!approve) {
            if (!agentStateService.denyApproval(runId)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行状态已变化，请刷新后重试");
            }
            String text = rejectionText(reason);
            fillUnresolvedBatch(run, pending, text);
            appendInterruptEvent(runId, run.getConversationId(),
                    AgentRunStatusEnum.SUSPENDED.name(), null, null);
            return toVO(agentStateService.getRun(runId));
        }

        // 同意后要接着跑，必须保证这次运行的用户消息仍是会话最新一条（同 resume）
        requireInputMessageIsLatest(run);

        StreamingChatModel model = streamingChatModelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "对话模型未配置（缺少 ai.chat.api-key），无法继续");
        }
        Conversation conversation = conversationService.getById(run.getConversationId());
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }

        RunContext ctx = new RunContext(run.getUserId(), run.getConversationId(),
                parseKbIds(conversation.getKbIds()));
        // 先重建 prompt（可能查库失败），再翻转状态——避免翻成 RUNNING 后却没有循环在跑
        List<ChatMessage> base = memoryBuilder.build(run.getConversationId(), ChatServiceImpl.SYSTEM_PROMPT);

        // 条件翻转 WAITING_APPROVAL → RUNNING：双击「同意」时只有一个请求能继续
        if (!agentStateService.approveRun(runId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行状态已变化，请刷新后重试");
        }
        agentStreamHub.create(runId, null);
        AgentEventSink sink = agentStreamHub.sink(runId);

        agentExecutor.execute(() -> {
            try {
                if (!completeApprovedBatch(ctx, run, pending, sink)) {
                    return; // 又遇到需确认的工具，已由 suspendForApproval 收敛
                }
                List<AgentStep> steps = agentStateService.listSteps(runId);
                ResumePlanner.Result plan = ResumePlanner.reconstruct(base, steps);
                // 之前已完成的轮会被重放；本次刚执行的那一步（pending）不算重放
                logReplayedSteps(run, steps, plan.replayedStepIds(), pending.getId());
                if (plan.danglingSideEffect()) {
                    AgentStep dangling = findDanglingSideEffectStep(runId);
                    if (dangling != null) {
                        String warning = dangling.getIdempotencyKey() == null
                                ? null : AgentRunner.UNKNOWN_SIDE_EFFECT_WARNING;
                        agentRunner.suspendForApproval(runId, run.getConversationId(),
                                dangling.getStepNo() == null ? 0 : dangling.getStepNo(),
                                dangling, warning, sink);
                    }
                    return;
                }
                agentRunner.resume(model, ctx, runId, run.getInputMessageId(), plan.messages(),
                        plan.roundsCompleted(), plan.maxSeq(), run.getTokenIn(), run.getTokenOut(), sink);
            } catch (Exception e) {
                log.warn("审批后继续执行失败: runId={}", runId, e);
                AgentRun now = agentStateService.getRun(runId);
                if (now != null && !AgentRunStatusEnum.FAILED.name().equals(now.getStatus())) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    agentStateService.failRun(runId, msg);
                    emitError(sink, run.getConversationId(), runId, msg);
                }
            } finally {
                agentStreamHub.finish(runId);
            }
        });

        return toVO(agentStateService.getRun(runId));
    }

    @Override
    public AgentRunVO getRun(long runId, Long userId) {
        return toVO(requireOwned(runId, userId));
    }

    @Override
    public List<AgentStepVO> listSteps(long runId, Long userId) {
        requireOwned(runId, userId);
        return agentStateService.listSteps(runId).stream()
                .map(AgentStepVO::from)
                .toList();
    }

    @Override
    public IPage<AgentRunVO> listRuns(Long conversationId, Long userId, int current, int pageSize) {
        if (conversationId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
        conversationService.getOwnedOrThrow(conversationId, userId);
        return agentStateService.pageRuns(conversationId, current, pageSize).convert(this::toVO);
    }

    @Override
    public SseEmitter reconnect(long runId, Long userId, Long lastEventId) {
        AgentRun run = requireOwned(runId, userId);
        long from = lastEventId == null ? 0L : lastEventId;
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Runnable stopHeartbeat = sseHeartbeat.start(emitter);
        emitter.onCompletion(stopHeartbeat);
        emitter.onTimeout(stopHeartbeat);
        emitter.onError(t -> stopHeartbeat.run());

        // 运行还在跑：挂到活通道上（hub 在锁内补发 + 发快照 + 换 emitter），之后继续推新事件
        if (agentStreamHub.attach(runId, emitter, from)) {
            return emitter;
        }

        // 已无活通道（完成 / 挂起 / 失败）：纯库里补发；挂起的再补一条部分文本快照，然后收尾
        for (AgentEvent ev : agentStateService.listEventsAfter(runId, from)) {
            send(emitter, ev.getId(), ev.getEventType(), parseJson(ev.getDataJson()));
        }
        String partial = interruptedText(runId);
        if (partial != null && !partial.isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("messageId", null);
            d.put("text", partial);
            send(emitter, null, "message_snapshot", d);
        }
        emitter.complete();
        return emitter;
    }

    // ---------------- 人工确认的后续执行（M10） ----------------

    /** 已认领但无结果时，用户点「同意」只代表已知晓，绝不能重跑。 */
    private static final String ACK_UNKNOWN_TEXT =
            "用户已确认：上次执行结果未知，系统未重复执行该操作。请人工核实实际结果。";

    /**
     * 用户同意后：先执行当前待确认步骤，再把同一批次里剩余的工具调用补齐。
     *
     * @return true = 本批次已全部有结果，可以走 {@code ResumePlanner} 继续；
     *         false = 又遇到需确认的工具/结果未知，已重新挂到 {@code WAITING_APPROVAL}
     */
    private boolean completeApprovedBatch(RunContext ctx, AgentRun run, AgentStep pending,
                                          AgentEventSink sink) {
        // 1. 当前待确认行可能不属于最新批次（例如服务重启后从旧轮转来人工确认）
        if (pending.getToolResultJson() == null) {
            if (pending.getIdempotencyKey() != null) {
                fillNotExecuted(pending, ACK_UNKNOWN_TEXT, run, sink);
            } else {
                ToolExecutionRequest req = requestOf(pending);
                AgentRunner.ToolExecutionOutcome out = isSideEffect(pending)
                        ? agentRunner.executeSideEffectTool(ctx, run.getId(), pending, req, sink)
                        : agentRunner.executeReadOnlyTool(ctx, run.getId(), pending, req, sink);
                if (out.needsApproval()) {
                    if (out.pendingStep() != null
                            && !out.pendingStep().getId().equals(pending.getId())) {
                        fillNotExecuted(pending, AgentRunner.SIDE_EFFECT_CONFLICT_TEXT, run, sink);
                    }
                    agentRunner.suspendForApproval(run.getId(), run.getConversationId(),
                            pending.getStepNo() == null ? 0 : pending.getStepNo(),
                            out.pendingStep(), out.warning(), sink);
                    return false;
                }
            }
        }

        // 2. 补齐最新批次里剩余的工具调用（保持模型请求的顺序）
        List<AgentStep> steps = agentStateService.listSteps(run.getId());
        AgentStep llm = latestSucceededLlm(steps);
        if (llm == null) {
            return true;
        }
        int seq = maxSeq(steps);
        for (ToolExecutionRequest req : ResumePlanner.toolRequestsOf(llm.getResponseJson())) {
            AgentStep step = findToolStep(steps, req);
            if (step != null && step.getToolResultJson() != null) {
                continue;
            }

            if (step == null) {
                seq++;
                boolean sideEffect = toolRegistry.sideEffect(req.name());
                boolean requireApproval = toolRegistry.requireApproval(req.name());
                long stepId = agentStateService.beginToolStep(run.getId(), llm.getStepNo(), seq,
                        req.id(), req.name(), req.arguments(), sideEffect, requireApproval);
                step = stepView(stepId, llm.getStepNo(), req);
                steps.add(step);
                emitToolCall(run.getId(), run.getConversationId(), step, req, sideEffect, sink);

                if (requireApproval) {
                    agentRunner.suspendForApproval(run.getId(), run.getConversationId(),
                            llm.getStepNo(), step, null, sink);
                    return false;
                }
            }

            AgentRunner.ToolExecutionOutcome out = isSideEffect(step)
                    ? agentRunner.executeSideEffectTool(ctx, run.getId(), step, req, sink)
                    : agentRunner.executeReadOnlyTool(ctx, run.getId(), step, req, sink);

            if (out.needsApproval()) {
                if (out.pendingStep() != null && !out.pendingStep().getId().equals(step.getId())) {
                    fillNotExecuted(step, AgentRunner.SIDE_EFFECT_CONFLICT_TEXT, run, sink);
                }
                agentRunner.suspendForApproval(run.getId(), run.getConversationId(),
                        llm.getStepNo(), out.pendingStep(), out.warning(), sink);
                return false;
            }
        }
        return true;
    }

    /**
     * 拒绝/停止时：把当前待确认步骤和本批次剩余未执行的调用全部写成「未执行」结果。
     *
     * <p>为什么必须补齐：ResumePlanner 只重放完整轮；只要有工具调用缺结果，整轮会被丢弃，
     * 用户看到的拒绝原因就传不给模型（knowledge/12 ⑦ 的硬约束）。
     */
    private void fillUnresolvedBatch(AgentRun run, AgentStep pending, String text) {
        List<AgentStep> steps = agentStateService.listSteps(run.getId());
        Set<Long> resolved = new HashSet<>();

        AgentStep llm = latestSucceededLlm(steps);
        int seq = maxSeq(steps);
        if (llm != null) {
            for (ToolExecutionRequest req : ResumePlanner.toolRequestsOf(llm.getResponseJson())) {
                AgentStep step = findToolStep(steps, req);
                if (step == null) {
                    seq++;
                    boolean sideEffect = toolRegistry.sideEffect(req.name());
                    boolean requireApproval = toolRegistry.requireApproval(req.name());
                    long stepId = agentStateService.beginToolStep(run.getId(), llm.getStepNo(), seq,
                            req.id(), req.name(), req.arguments(), sideEffect, requireApproval);
                    step = stepView(stepId, llm.getStepNo(), req);
                    steps.add(step);
                    appendToolCall(run, step, req, sideEffect);
                }
                if (step.getToolResultJson() == null && resolved.add(step.getId())) {
                    agentStateService.finishToolStep(step.getId(), text, 0L,
                            AgentStepStatusEnum.FAILED, null);
                    appendToolResult(run, step.getId(), req.id(), false, text);
                }
            }
        }

        // 兜底：当前待确认行不在最新批次里（例如旧轮），也要封掉
        if (pending.getToolResultJson() == null && resolved.add(pending.getId())) {
            agentStateService.finishToolStep(pending.getId(), text, 0L,
                    AgentStepStatusEnum.FAILED, null);
            appendToolResult(run, pending.getId(), pending.getToolCallId(), false, text);
        }
    }

    private void fillNotExecuted(AgentStep step, String text, AgentRun run, AgentEventSink sink) {
        agentStateService.finishToolStep(step.getId(), text, 0L, AgentStepStatusEnum.FAILED, null);
        emitToolResult(run.getId(), run.getConversationId(), step.getId(),
                step.getToolCallId(), false, 0L, text, sink);
    }

    private static ToolExecutionRequest requestOf(AgentStep step) {
        return ToolExecutionRequest.builder()
                .id(step.getToolCallId() == null || step.getToolCallId().isBlank()
                        ? "step-" + step.getId() : step.getToolCallId())
                .name(step.getToolName())
                .arguments(step.getArgumentsJson() == null ? "{}" : step.getArgumentsJson())
                .build();
    }

    private static AgentStep latestSucceededLlm(List<AgentStep> steps) {
        AgentStep latest = null;
        for (AgentStep s : steps) {
            if (!AgentStepTypeEnum.LLM_CALL.name().equals(s.getType())
                    || !AgentStepStatusEnum.SUCCEEDED.name().equals(s.getStatus())) {
                continue;
            }
            if (latest == null || (s.getSeq() != null && latest.getSeq() != null
                    && s.getSeq() > latest.getSeq())) {
                latest = s;
            }
        }
        return latest;
    }

    private static AgentStep findToolStep(List<AgentStep> steps, ToolExecutionRequest req) {
        for (AgentStep s : steps) {
            if (!AgentStepTypeEnum.TOOL_CALL.name().equals(s.getType())) {
                continue;
            }
            if (req.id() != null && !req.id().isBlank()) {
                if (req.id().equals(s.getToolCallId())) {
                    return s;
                }
            } else if (req.name() != null && req.name().equals(s.getToolName())) {
                return s;
            }
        }
        return null;
    }

    private static int maxSeq(List<AgentStep> steps) {
        return steps.stream()
                .mapToInt(s -> s.getSeq() == null ? 0 : s.getSeq())
                .max().orElse(0);
    }

    private static AgentStep stepView(long stepId, int stepNo, ToolExecutionRequest req) {
        AgentStep step = new AgentStep();
        step.setId(stepId);
        step.setStepNo(stepNo);
        step.setToolCallId(req.id());
        step.setToolName(req.name());
        step.setArgumentsJson(req.arguments());
        return step;
    }

    private static boolean isSideEffect(AgentStep step) {
        return step.getSideEffect() != null && step.getSideEffect() == 1;
    }

    private AgentStep findDanglingSideEffectStep(long runId) {
        AgentStep found = null;
        for (AgentStep s : agentStateService.listSteps(runId)) {
            if (AgentStepTypeEnum.TOOL_CALL.name().equals(s.getType())
                    && isSideEffect(s) && s.getToolResultJson() == null) {
                found = s;
            }
        }
        return found;
    }

    /**
     * 续跑/审批时把「从库里重放」的工具步记为 replayed=1，并写一条 ai_tool_log（replayed=1）。
     * 这是 M12 验收里「重放机制真的走了」的证据。
     */
    private void logReplayedSteps(AgentRun run, List<AgentStep> steps,
                                  List<Long> replayedStepIds, Long excludeStepId) {
        for (Long stepId : replayedStepIds) {
            if (excludeStepId != null && excludeStepId.equals(stepId)) {
                continue;
            }
            AgentStep step = null;
            for (AgentStep candidate : steps) {
                if (candidate.getId().equals(stepId)) {
                    step = candidate;
                    break;
                }
            }
            if (step == null) {
                continue;
            }
            agentStateService.markReplayed(stepId);
            boolean success = !AgentStepStatusEnum.FAILED.name().equals(step.getStatus());
            observabilityService.logToolCall(new ObservabilityService.ToolCallContext(
                    run.getId(), stepId, run.getUserId(), step.getToolName(),
                    toolRegistry.source(step.getToolName()),
                    step.getSideEffect() != null && step.getSideEffect() == 1,
                    step.getArgumentsJson(), step.getToolResultJson(),
                    success, success ? null : step.getToolResultJson(),
                    0L, true, step.getIdempotencyKey()));
        }
    }

    private static String rejectionText(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() > 500) {
            r = r.substring(0, 500);
        }
        return r.isEmpty()
                ? "用户拒绝执行该操作，操作未执行。"
                : "用户拒绝执行该操作，操作未执行。原因：" + r;
    }

    private AgentRunVO toVO(AgentRun run) {
        PendingToolVO pending = null;
        if (run != null && AgentRunStatusEnum.WAITING_APPROVAL.name().equals(run.getStatus())) {
            AgentStep step = agentStateService.findPendingApprovalStep(run.getId());
            if (step != null) {
                String warning = step.getIdempotencyKey() == null
                        ? null : AgentRunner.UNKNOWN_SIDE_EFFECT_WARNING;
                pending = toolRegistry.pendingTool(step, warning);
            }
        }
        return AgentRunVO.from(run, pending);
    }

    private Map<String, Object> pendingToolData(AgentStep step, String warning) {
        PendingToolVO vo = toolRegistry.pendingTool(step, warning);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stepId", String.valueOf(step.getId()));
        d.put("toolName", vo.getToolName());
        d.put("toolDisplayName", vo.getToolDisplayName());
        d.put("toolDescription", vo.getToolDescription());
        d.put("arguments", vo.getArguments());
        if (warning != null) {
            d.put("warning", warning);
        }
        return d;
    }

    private void appendApprovalInterruptEvent(long runId, Long conversationId,
                                              AgentStep step, String warning) {
        appendInterruptEvent(runId, conversationId, AgentRunStatusEnum.WAITING_APPROVAL.name(),
                InterruptTypeEnum.APPROVAL_REQUIRED.name(), pendingToolData(step, warning));
    }

    private void appendInterruptEvent(long runId, Long conversationId, String status,
                                      String interruptType, Map<String, Object> pendingTool) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", String.valueOf(runId));
        data.put("status", status);
        data.put("interruptType", interruptType);
        if (pendingTool != null) {
            data.put("pendingTool", pendingTool);
        }
        agentStateService.appendEvent(runId, conversationId,
                AgentEventTypeEnum.INTERRUPT, toJson(data));
    }

    private void emitToolCall(long runId, Long conversationId, AgentStep step,
                              ToolExecutionRequest req, boolean sideEffect, AgentEventSink sink) {
        Map<String, Object> data = AgentRunner.toolCallData(String.valueOf(step.getId()),
                req.id(), req.name(), req.arguments(), sideEffect);
        long eventId = agentStateService.appendEvent(runId, conversationId,
                AgentEventTypeEnum.TOOL_CALL, toJson(data));
        sink.onEvent(eventId, AgentEventTypeEnum.TOOL_CALL.code(), data);
    }

    private void appendToolCall(AgentRun run, AgentStep step, ToolExecutionRequest req,
                                boolean sideEffect) {
        Map<String, Object> data = AgentRunner.toolCallData(String.valueOf(step.getId()),
                req.id(), req.name(), req.arguments(), sideEffect);
        agentStateService.appendEvent(run.getId(), run.getConversationId(),
                AgentEventTypeEnum.TOOL_CALL, toJson(data));
    }

    private void emitToolResult(long runId, Long conversationId, long stepId, String toolCallId,
                                boolean success, long elapsedMs, String text, AgentEventSink sink) {
        Map<String, Object> data = AgentRunner.toolResultData(String.valueOf(stepId), toolCallId,
                success, elapsedMs, text, false);
        long eventId = agentStateService.appendEvent(runId, conversationId,
                AgentEventTypeEnum.TOOL_RESULT, toJson(data));
        sink.onEvent(eventId, AgentEventTypeEnum.TOOL_RESULT.code(), data);
    }

    private void appendToolResult(AgentRun run, long stepId, String toolCallId,
                                  boolean success, String text) {
        Map<String, Object> data = AgentRunner.toolResultData(String.valueOf(stepId), toolCallId,
                success, 0L, text, false);
        agentStateService.appendEvent(run.getId(), run.getConversationId(),
                AgentEventTypeEnum.TOOL_RESULT, toJson(data));
    }

    private void emitError(AgentEventSink sink, Long conversationId, long runId, String msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", 50001);
        data.put("message", "模型调用失败：" + msg);
        data.put("runId", String.valueOf(runId));
        data.put("retryable", false);
        long eventId = agentStateService.appendEvent(runId, conversationId,
                AgentEventTypeEnum.ERROR, toJson(data));
        sink.onEvent(eventId, AgentEventTypeEnum.ERROR.code(), data);
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------- 内部 ----------------

    private AgentRun requireOwned(long runId, Long userId) {
        AgentRun run = agentStateService.getRun(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "运行不存在");
        }
        if (!run.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该运行");
        }
        return run;
    }

    /**
     * 续跑护栏：本次运行的用户消息必须仍是会话最新一条。
     * 否则 {@code MemoryBuilder} 重建的基础历史会把「该运行之后的新消息」也带进来，续跑的推理就错位了。
     */
    private void requireInputMessageIsLatest(AgentRun run) {
        List<ConversationMessage> messages = conversationMessageService.listPromptVisibleAsc(run.getConversationId());
        Long latestId = messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();
        if (latestId == null || !latestId.equals(run.getInputMessageId())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "该运行之后会话又有新消息，无法继续——请直接重新提问");
        }
    }

    /** 挂起的运行里，最后一条被中断的 LLM 步的文本（重连时作为部分文本快照）。 */
    private String interruptedText(long runId) {
        String text = null;
        for (AgentStep s : agentStateService.listSteps(runId)) {
            if (AgentStepTypeEnum.LLM_CALL.name().equals(s.getType())
                    && AgentStepStatusEnum.INTERRUPTED.name().equals(s.getStatus())) {
                text = ResumePlanner.textOf(s.getResponseJson());
            }
        }
        return text;
    }

    private void send(SseEmitter emitter, Long id, String event, Object data) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (id != null) {
                builder.id(String.valueOf(id));
            }
            builder.name(event).data(data, MediaType.APPLICATION_JSON);
            emitter.send(builder.build());
        } catch (IOException | IllegalStateException e) {
            log.warn("重连补发失败: runId={}, event={}, err={}", event, e.getMessage());
        }
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 会话关联的知识库串 → id 列表；非数字项跳过（历史脏数据容错）。 */
    private static List<Long> parseKbIds(String raw) {
        List<Long> ids = new ArrayList<>();
        for (String s : ConversationServiceImpl.parseKbIds(raw)) {
            try {
                ids.add(Long.valueOf(s.trim()));
            } catch (NumberFormatException ignored) {
                // 跳过
            }
        }
        return ids;
    }
}
