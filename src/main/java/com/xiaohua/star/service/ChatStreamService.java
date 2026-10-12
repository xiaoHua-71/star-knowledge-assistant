package com.xiaohua.star.service;

import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话服务（M6 最小可用版：无检索、无 run、无中断/重连）。见 frontend/02、frontend/30。
 *
 * <p>建流阶段（参数 / 会话归属 / 模型装配）失败抛
 * {@link com.xiaohua.star.exception.BusinessException}，由 {@code ChatController} 的
 * 控制器局部 {@code @ExceptionHandler} 转成 {@code application/json} 的普通信封（HTTP 200）。
 * 建流成功后返回的 {@link SseEmitter} 由模型回调驱动推送事件。
 */
public interface ChatStreamService {

    /**
     * 发消息并建立 SSE 流。返回的 emitter 已在调用线程发出 {@code run_start}
     * （此时尚未 initialize，事件会被缓冲并按序回放）。
     */
    SseEmitter stream(ChatMessageRequest request, Long userId);
}
