package com.xiaohua.star.model.dto.conversation;

import lombok.Data;

import java.util.List;

/**
 * 非流式对话请求。对应 {@code POST /chat/message}（见 frontend/30）。
 *
 * <p>字段与流式接口 {@code POST /chat/stream} 保持一致，便于将来 M6 加流式时复用同一形状。
 * 但本接口是<b>简化路径</b>：不检索、不建 run。
 */
@Data
public class ChatMessageRequest {

    private Long conversationId;

    /**
     * 用户消息，1~4000 字符（按字符数算）
     */
    private String content;

    /**
     * 本次要用哪些知识库检索。<b>当前版本忽略</b>（无检索），保留字段仅为与 /chat/stream 形状一致。
     */
    private List<String> kbIds;
}
