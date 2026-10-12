package com.xiaohua.star.model.dto.conversation;

import lombok.Data;

/**
 * 截断重发请求。对应 {@code POST /conversation/message/delete}。
 *
 * <p>语义是<b>截断</b>：删掉 {@code seq >= fromSeq} 的所有消息，不是删单条。
 */
@Data
public class MessageTruncateRequest {

    private Long conversationId;

    /**
     * 从这个序号起（含）全部删除
     */
    private Integer fromSeq;
}
