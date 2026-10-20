package com.xiaohua.star.service.impl;

import com.xiaohua.star.agent.AgentEventSink;
import com.xiaohua.star.agent.AgentRunner;
import com.xiaohua.star.agent.AgentStreamHub;
import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.agent.RunInterruptFlag;
import com.xiaohua.star.ai.MemoryBuilder;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.AgentExecutorConfig;
import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentRunStatusEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ChatStreamService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 流式对话实现（M6 消息流 + M7 工具循环 + M8 状态落库 + M9 中断/续跑接线）。
 * 见 frontend/02、frontend/30、knowledge/12。
 *
 * <p>本类只做<b>接入与传输</b>：校验 / 会话归属 / 取模型 / 落用户消息 / 重建 prompt /
 * <b>创建 run</b> / 建 SSE 并登记进 {@link AgentStreamHub}，然后把 {@link AgentRunner#run} 提交到
 * {@code agentExecutor}。**运行状态、事件、助手消息的落库、挂起/续跑全在 {@code AgentRunner}**；
 * 传输走 hub（同一 runId 的活通道），断线重连由 {@code AgentRunService#reconnect} 接。
 *
 * <p>M9 起 {@code createRun} 挪到这里（循环之前），因为 hub 的通道要以 {@code runId} 为键、
 * 必须在提交循环前就登记好。
 */
@Slf4j
@Service
public class ChatStreamServiceImpl implements ChatStreamService {

    /** SSE 超时。必须显式：否则退回容器默认 ~30s，会在长回答中途掐断。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** 对话模型可能不存在（没配 ai.chat.api-key 时不建 bean），用 ObjectProvider 容错。 */
    @Resource
    private ObjectProvider<StreamingChatModel> streamingChatModelProvider;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ConversationMessageService conversationMessageService;

    @Resource
    private MemoryBuilder memoryBuilder;

    @Resource
    private AgentRunner agentRunner;

    @Resource
    private AgentStateService agentStateService;

    @Resource
    private AgentStreamHub agentStreamHub;

    @Resource
    private RunInterruptFlag interruptFlag;

    @Resource
    private AgentProperties agentProperties;

    @Resource(name = AgentExecutorConfig.AGENT_EXECUTOR)
    private Executor agentExecutor;

    @Override
    public SseEmitter stream(ChatMessageRequest request, Long userId) {
        // 1. 参数校验（40000）
        if (request.getConversationId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
        String content = request.getContent();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "content 不能为空");
        }
        if (content.length() > ChatServiceImpl.MAX_CONTENT_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "content 超长（最多 " + ChatServiceImpl.MAX_CONTENT_LEN + " 字符）");
        }

        // 2. 校验会话归属（40400 / 40101）
        Conversation conversation = conversationService.getOwnedOrThrow(request.getConversationId(), userId);

        // 3. 取流式模型；缺则 50001，且在此之前不写任何数据（避免留下孤立的用户消息）
        StreamingChatModel model = streamingChatModelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "对话模型未配置（缺少 ai.chat.api-key / DEEPSEEK_API_KEY），无法对话");
        }

        // 4. 会话里若还有未结束的运行，先收敛它
        Long activeRunId = agentStateService.findActiveRunId(request.getConversationId());
        if (activeRunId != null) {
            AgentRun active = agentStateService.getRun(activeRunId);
            if (active != null && AgentRunStatusEnum.WAITING_APPROVAL.name().equals(active.getStatus())) {
                // 用户不等确认了（直接发了新消息）：没有循环线程可读中断标志，直接挂起
                agentStateService.suspendRun(activeRunId, InterruptTypeEnum.NEW_MESSAGE);
            } else {
                // 在跑的运行：登记标志，由它在下一个安全点自行挂起
                interruptFlag.set(activeRunId, InterruptTypeEnum.NEW_MESSAGE);
            }
        }

        // 5. 落用户消息（事务 #1）。tokenCount 记估算值。
        ConversationMessage userMsg = conversationMessageService.appendMessage(
                request.getConversationId(), userId,
                ConversationMessage.ROLE_USER, content, memoryBuilder.estimate(content));

        // 6. 运行上下文：工具经 @ToolMemoryId 拿到它（userId / 会话 / 生效的知识库范围）
        RunContext ctx = new RunContext(userId, request.getConversationId(),
                resolveKbIds(request, conversation));

        // 7. 重建 prompt（系统消息 + 预算内历史；末条即刚落的用户消息）
        List<ChatMessage> prompt = memoryBuilder.build(
                request.getConversationId(), ChatServiceImpl.SYSTEM_PROMPT);

        // 8. 创建运行 + 建流并登记进 hub（循环之外先建好，通道以 runId 为键）
        long runId = agentStateService.createRun(
                request.getConversationId(), userId, userMsg.getId(), agentProperties.getMaxRounds());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> log.warn("SSE 超时未结束: conversationId={}, runId={}", request.getConversationId(), runId));
        emitter.onError(t -> log.warn("SSE 异步错误: runId={}", runId, t));
        emitter.onCompletion(() -> log.debug("SSE 完成: runId={}", runId));
        agentStreamHub.create(runId, emitter);
        AgentEventSink sink = agentStreamHub.sink(runId);

        // 9. 跑落库循环（提交到 agentExecutor，请求线程立即释放）；收尾由 hub 完成 emitter
        agentExecutor.execute(() -> {
            try {
                agentRunner.run(model, ctx, runId, userMsg.getId(), prompt, sink);
            } catch (Exception e) {
                // 错误事件已由 AgentRunner（经 sink）推给前端，这里只记日志
                log.warn("Agent 运行失败: runId={}", runId, e);
            } finally {
                agentStreamHub.finish(runId);
            }
        });

        return emitter;
    }

    /**
     * 解析本次对话要用哪些知识库，按 frontend/30 的 {@code kbIds} 契约：
     * 请求带了就用请求的（临时覆盖，不改会话长期关联）；没带就用会话关联的；空数组表示本次不用 RAG。
     * 非数字项跳过（会话串是历史遗留脏数据时的容错）。
     */
    static List<Long> resolveKbIds(ChatMessageRequest request, Conversation conversation) {
        List<String> raw = request.getKbIds() != null
                ? request.getKbIds()
                : ConversationServiceImpl.parseKbIds(conversation.getKbIds());
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(s.trim()));
            } catch (NumberFormatException e) {
                log.warn("忽略非法的 kbId: {}", s);
            }
        }
        return ids;
    }
}
