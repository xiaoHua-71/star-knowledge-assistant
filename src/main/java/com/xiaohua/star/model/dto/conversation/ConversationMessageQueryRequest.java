package com.xiaohua.star.model.dto.conversation;

import com.xiaohua.star.common.PageRequest;
import lombok.EqualsAndHashCode;
import lombok.Data;

/**
 * 拉取历史消息的分页请求（{@code GET /conversation/{id}/messages}）。
 *
 * <p>除分页外无额外筛选条件，独立成类是为了让 controller 签名与知识库那套一致，
 * 也便于将来加「按角色过滤」之类的条件。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ConversationMessageQueryRequest extends PageRequest {
}
