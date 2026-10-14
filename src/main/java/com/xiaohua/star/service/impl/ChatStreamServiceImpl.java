package com.xiaohua.star.service.impl;

import com.xiaohua.star.agent.AgentEventSink;
import com.xiaohua.star.agent.AgentRunner;
import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.ai.MemoryBuilder;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.AgentExecutorConfig;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.service.ChatStreamService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式对话实现（M6 消息流 + M7 工具循环 + M8 状态落库）。见 frontend/02、frontend/30、knowledge/12。
 *
 * <p>本类只做<b>接入与传输</b>：校验 / 会话归属 / 取模型 / 事务#1 落用户消息 / 重建 prompt / 建 SSE，
 * 然后把 {@link AgentRunner#run} 提交到 {@code agentExecutor}。**运行状态、事件、助手消息的落库全在
 * {@code AgentRunner}**，它通过 {@link AgentEventSink} 把事件推回这里——这里只负责转成 SSE。
 *
 * <p>M8 起 {@code runId} 是真实的 {@code agent_run.id}（不再是占位 UUID），事件的 `id` 是
 * {@code agent_event.id}；`message_delta` 不落库、不带 `id`（重连靠 `message_snapshot`，属 M9）。
 *
 * <p><b>客户端断开后循环仍会跑完并落库</b>（只有推流被 sink 跳过）——取消逻辑属 M9。
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

        // 4. 落用户消息（事务 #1）。tokenCount 记估算值。
        ConversationMessage userMsg = conversationMessageService.appendMessage(
                request.getConversationId(), userId,
                ConversationMessage.ROLE_USER, content, memoryBuilder.estimate(content));

        // 5. 运行上下文：工具经 @ToolMemoryId 拿到它（userId / 会话 / 生效的知识库范围）
        RunContext ctx = new RunContext(userId, request.getConversationId(),
                resolveKbIds(request, conversation));

        // 6. 重建 prompt（系统消息 + 预算内历史；末条即刚落的用户消息）
        List<ChatMessage> prompt = memoryBuilder.build(
                request.getConversationId(), ChatServiceImpl.SYSTEM_PROMPT);

        // 7. 建 emitter 与事件出口（loop 先 run，sink 只负责推 SSE；生命周期由本方法收尾）
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean clientGone = new AtomicBoolean(false);

        emitter.onTimeout(() -> log.warn("SSE 超时未结束: conversationId={}", request.getConversationId()));
        emitter.onError(t -> log.warn("SSE 异步错误: conversationId={}", request.getConversationId(), t));
        emitter.onCompletion(() -> log.debug("SSE 完成: conversationId={}", request.getConversationId()));

        AgentEventSink sink = new AgentEventSink() {
            @Override
            public void onEvent(long eventId, String eventName, Object data) {
                send(emitter, clientGone, eventId, eventName, data);
            }

            @Override
            public void onDelta(String delta) {
                send(emitter, clientGone, null, "message_delta", Map.of("delta", delta));
            }
        };

        // 8. 跑落库循环（提交到 agentExecutor，请求线程立即释放）
        agentExecutor.execute(() -> {
            try {
                agentRunner.run(model, ctx, userMsg.getId(), prompt, sink);
            } catch (Exception e) {
                // 错误事件已由 AgentRunner（经 sink）推给前端，这里只记日志
                log.warn("Agent 运行失败: conversationId={}", request.getConversationId(), e);
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 发一个 SSE 事件。客户端已断则静默跳过；发送失败只置标志（循环不需要知道连接状态）。
     *
     * @param id 里程碑事件用 {@code agent_event.id}；{@code message_delta} 传 {@code null}（不落库、不可重放）
     */
    private void send(SseEmitter emitter, AtomicBoolean clientGone, Long id, String event, Object data) {
        if (clientGone.get()) {
            return;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (id != null) {
                builder.id(String.valueOf(id));
            }
            builder.name(event).data(data, MediaType.APPLICATION_JSON);
            emitter.send(builder.build());
        } catch (IOException e) {
            clientGone.set(true);
        } catch (IllegalStateException ignored) {
            // emitter 已完成：收尾竞态，忽略
        }
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
