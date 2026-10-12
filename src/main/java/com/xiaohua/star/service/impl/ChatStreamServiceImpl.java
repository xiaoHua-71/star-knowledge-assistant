package com.xiaohua.star.service.impl;

import com.xiaohua.star.ai.MemoryBuilder;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.service.ChatStreamService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式对话实现（M6 最小可用版：无检索、无 run、无中断/重连）。见 frontend/02、frontend/30。
 *
 * <p>与非流式 {@link ChatServiceImpl} 同构：建流阶段（校验 / 归属 / 取模型 / 事务#1 落用户消息 /
 * 重建 prompt）在调用线程同步完成；模型调用异步非阻塞，逐段回答经回调在 HTTP 客户端线程推
 * {@code message_delta}；正常结束在回调里做事务#2 落助手消息，再推 {@code run_end}。
 *
 * <p>两个短事务跨 bean 调用（走 {@code @Transactional} 代理），模型调用在事务之外。
 *
 * <p>runId 目前是占位 UUID（无 agent_run 表）；事件 id 用内存自增游标，不持久化、不可重放——
 * 这些是 M8 的 run / 事件持久化才补。
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
        conversationService.getOwnedOrThrow(request.getConversationId(), userId);

        // 3. 取流式模型；缺则 50001，且在此之前不写任何数据（避免留下孤立的用户消息）
        StreamingChatModel model = streamingChatModelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "对话模型未配置（缺少 ai.chat.api-key / DEEPSEEK_API_KEY），无法对话");
        }

        // 4. 落用户消息（事务 #1，跨 bean 短事务）。tokenCount 记估算值。
        ConversationMessage userMsg = conversationMessageService.appendMessage(
                request.getConversationId(), userId,
                ConversationMessage.ROLE_USER, content, memoryBuilder.estimate(content));

        // 5. 重建 prompt（含系统消息 + 预算内历史，末条即刚落的用户消息）
        List<ChatMessage> prompt = memoryBuilder.build(
                request.getConversationId(), ChatServiceImpl.SYSTEM_PROMPT);

        // 6. 建 emitter，先发 run_start。此时尚未 initialize，事件会被缓冲并按序回放，不丢。
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String runId = UUID.randomUUID().toString();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicLong seq = new AtomicLong();

        emitter.onTimeout(() -> {
            log.warn("SSE 超时未结束: conversationId={}", request.getConversationId());
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(t -> log.warn("SSE 异步错误: conversationId={}", request.getConversationId(), t));
        emitter.onCompletion(() -> log.debug("SSE 完成: conversationId={}", request.getConversationId()));

        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(seq.incrementAndGet()))
                    .name("run_start")
                    .data(Map.of(
                                    "runId", runId,
                                    "conversationId", request.getConversationId(),
                                    "messageId", userMsg.getId()),
                            MediaType.APPLICATION_JSON)
                    .build());
        } catch (IOException e) {
            // 初始化前的 send 走缓冲，理论上不抛；保险起见按断开处理
            clientGone.set(true);
        }

        // 7. 调模型（异步非阻塞，立即返回；回调在 HTTP 客户端线程）
        model.chat(prompt, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partial) {
                if (clientGone.get() || closed.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(seq.incrementAndGet()))
                            .name("message_delta")
                            .data(Map.of("delta", partial), MediaType.APPLICATION_JSON)
                            .build());
                } catch (IOException e) {
                    // 客户端断开：不落助手消息，直接收尾
                    clientGone.set(true);
                    closed.set(true);
                    emitter.complete();
                } catch (IllegalStateException e) {
                    closed.set(true); // emitter 已完成
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (clientGone.get() || closed.get()) {
                    closed.set(true);
                    return; // 客户端已断，不落库、不发送
                }
                String answer = response.aiMessage().text();
                Integer tokens = null;
                if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                    tokens = response.metadata().tokenUsage().totalTokenCount();
                }
                try {
                    // 事务 #2：只在正常结束时落助手消息
                    ConversationMessage assistant = conversationMessageService.appendMessage(
                            request.getConversationId(), userId,
                            ConversationMessage.ROLE_ASSISTANT, answer, tokens);

                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(seq.incrementAndGet()))
                            .name("run_end")
                            .data(Map.of(
                                            "runId", runId,
                                            "status", "COMPLETED",
                                            "messageId", assistant.getId()),
                                    MediaType.APPLICATION_JSON)
                            .build());
                    closed.set(true);
                    emitter.complete();
                } catch (IOException e) {
                    // run_end 发送失败（客户端已断）：助手消息已落库，仅记录
                    log.warn("run_end 发送失败(客户端断开): conversationId={}", request.getConversationId());
                    closed.set(true);
                    emitter.complete();
                } catch (Exception e) {
                    log.error("流式收尾失败: conversationId={}", request.getConversationId(), e);
                    sendError(emitter, seq, runId, ErrorCode.SYSTEM_ERROR, "收尾失败");
                    closed.set(true);
                    emitter.complete();
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("流式模型调用失败: conversationId={}", request.getConversationId(), t);
                sendError(emitter, seq, runId, ErrorCode.OPERATION_ERROR, "模型调用失败：" + t.getMessage());
                closed.set(true);
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 推 error 事件（协议里唯一带 code 的事件），客户端已断时静默忽略。
     */
    private void sendError(SseEmitter emitter, AtomicLong seq, String runId, ErrorCode code, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(seq.incrementAndGet()))
                    .name("error")
                    .data(Map.of(
                                    "code", code.getCode(),
                                    "message", message,
                                    "runId", runId,
                                    "retryable", false),
                            MediaType.APPLICATION_JSON)
                    .build());
        } catch (Exception ignored) {
            // 客户端已断开，无需再报错
        }
    }
}
