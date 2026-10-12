package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话（用户可见的「一次对话」容器）。
 *
 * <p>有 {@code isDelete} → 走全局逻辑删：{@code selectById}/{@code selectPage} 自动追加
 * {@code AND isDelete = 0}，{@code removeById} 变成 {@code UPDATE isDelete = 1}。
 *
 * <p>{@code messageCount} 是双职责字段：既用于列表展示「共几条消息」，又是下一条消息
 * {@code seq} 的发生器（{@code seq = messageCount + 1}）。两者必须在同一事务里更新——
 * 见 {@code ConversationMessageServiceImpl}（消息追加/截断处有行锁）。
 */
@TableName(value = "conversation")
@Data
public class Conversation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 会话标题，创建时默认「新会话」
     */
    private String title;

    /**
     * 关联知识库 id，逗号分隔字符串；null 表示不用 RAG
     */
    private String kbIds;

    /**
     * 是否置顶，0/1
     */
    private Integer pinned;

    /**
     * 消息数（冗余），同时用于生成下一条消息的 seq
     */
    private Integer messageCount;

    /**
     * 最后一条消息时间，列表排序用
     */
    private LocalDateTime lastMessageTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
