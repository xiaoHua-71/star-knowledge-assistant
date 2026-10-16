package com.xiaohua.star.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.agent.AgentEventSink;
import com.xiaohua.star.agent.AgentRunner;
import com.xiaohua.star.agent.AgentStreamHub;
import com.xiaohua.star.agent.ResumePlanner;
import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.agent.RunInterruptFlag;
import com.xiaohua.star.ai.MemoryBuilder;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.AgentExecutorConfig;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.entity.AgentEvent;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentRunStatusEnum;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
import com.xiaohua.star.model.vo.AgentRunVO;
import com.xiaohua.star.service.AgentRunService;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Agent 运行操作实现（M9）。见 knowledge/12-中断与恢复的状态机.md、frontend/40。
 *
 * <p>中断 = 只能「登记请求」（写 Redis 标志），真正的挂起由运行线程在下个安全点完成——
 * 所以接口返回成功 ≠ 已经停了（frontend/40 反复强调这点）。
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
    private RunInterruptFlag interruptFlag;

    @Resource
    private ObjectProvider<StreamingChatModel> streamingChatModelProvider;

    @Resource(name = AgentExecutorConfig.AGENT_EXECUTOR)
    private Executor agentExecutor;

    @Override
    public boolean interrupt(long runId, Long userId) {
        AgentRun run = requireOwned(runId, userId);
        String status = run.getStatus();
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
        ResumePlanner.Result plan = ResumePlanner.reconstruct(base, agentStateService.listSteps(runId));
        if (plan.danglingSideEffect()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "该运行存在「已开始、结果未知」的副作用操作，需人工确认后才能继续（属 M10）");
        }

        // 条件翻转 SUSPENDED → RUNNING：false 说明已被别的请求续跑，别重复起循环
        if (!agentStateService.resumeRun(runId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行状态已变化，无法继续");
        }
        for (Long stepId : plan.replayedStepIds()) {
            agentStateService.markReplayed(stepId);
        }

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
        return AgentRunVO.from(agentStateService.getRun(runId));
    }

    @Override
    public AgentRunVO getRun(long runId, Long userId) {
        return AgentRunVO.from(requireOwned(runId, userId));
    }

    @Override
    public SseEmitter reconnect(long runId, Long userId, Long lastEventId) {
        AgentRun run = requireOwned(runId, userId);
        long from = lastEventId == null ? 0L : lastEventId;
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

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
