package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话视图对象。字段与 frontend/20 的 {@code ConversationVO} 一一对应。
 *
 * <p>{@code id} 声明为 {@code Long}：由 {@code JsonConfig} 全局序列化成 JSON 字符串
 * （前端按 string 处理），与既有 {@code KnowledgeBaseVO} 一致。
 */
@Data
public class ConversationVO implements Serializable {

    private Long id;

    private String title;

    private boolean pinned;

    private int messageCount;

    /**
     * 关联知识库 id 列表；空列表表示不用 RAG
     */
    private List<String> kbIds;

    private LocalDateTime lastMessageTime;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
