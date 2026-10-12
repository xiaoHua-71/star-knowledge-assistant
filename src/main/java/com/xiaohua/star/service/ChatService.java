package com.xiaohua.star.service;

import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.vo.MessageVO;

/**
 * 非流式对话服务（简化路径，见 knowledge/40 ③）。
 *
 * <p>无检索、无 run：只做「落用户消息 → 取历史 → 拼 prompt → 调模型 → 落助手消息」。
 */
public interface ChatService {

    /**
     * 发一条消息并一次性拿到回答。返回落库后的助手消息。
     */
    MessageVO chat(ChatMessageRequest request, Long userId);
}
