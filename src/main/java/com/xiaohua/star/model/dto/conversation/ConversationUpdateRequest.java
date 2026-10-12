package com.xiaohua.star.model.dto.conversation;

import lombok.Data;

/**
 * 修改会话请求（重命名 / 置顶）。对应 {@code POST /conversation/update}。
 *
 * <p><b>传了才改</b>：为 null 的字段保持不变。
 */
@Data
public class ConversationUpdateRequest {

    private Long id;

    /**
     * 新标题，1~255 字符
     */
    private String title;

    /**
     * 是否置顶
     */
    private Boolean pinned;
}
