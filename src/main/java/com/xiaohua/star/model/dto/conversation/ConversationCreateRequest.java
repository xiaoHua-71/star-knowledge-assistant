package com.xiaohua.star.model.dto.conversation;

import lombok.Data;

import java.util.List;

/**
 * 创建会话请求。对应 frontend/20 的 {@code POST /conversation/create}。
 */
@Data
public class ConversationCreateRequest {

    /**
     * 标题，不传则默认「新会话」
     */
    private String title;

    /**
     * 关联知识库 id 列表，空/不传表示不用 RAG
     */
    private List<String> kbIds;
}
