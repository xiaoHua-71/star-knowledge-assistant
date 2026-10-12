package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话消息视图对象。字段与 frontend/20 的 {@code MessageVO} 一一对应。
 *
 * <p>注意 {@code toolCalls} 是<b>工具名列表</b>（仅供展示），不是数据库里那份原始 tool_calls JSON；
 * 前者从后者**提取**而来（见 {@code ConversationMessageServiceImpl#parseToolCalls}）。M4 恒为 null。
 */
@Data
public class MessageVO implements Serializable {

    private Long id;

    /**
     * 会话内序号，从 1 开始。排序用这个，不要用 createTime。
     */
    private int seq;

    /**
     * USER / ASSISTANT
     */
    private String role;

    private String content;

    /**
     * 这条回答用过的工具名，仅供展示；无工具时为 null
     */
    private List<String> toolCalls;

    private boolean interrupted;

    private Integer tokenCount;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
