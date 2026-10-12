package com.xiaohua.star.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.star.common.PageRequest;
import com.xiaohua.star.model.dto.conversation.ConversationCreateRequest;
import com.xiaohua.star.model.dto.conversation.ConversationUpdateRequest;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.vo.ConversationVO;

/**
 * 会话服务。见 knowledge/40-会话与消息管理.md。
 */
public interface ConversationService extends IService<Conversation> {

    ConversationVO create(ConversationCreateRequest request, Long userId);

    IPage<ConversationVO> listPage(PageRequest request, Long userId);

    ConversationVO detail(Long id, Long userId);

    ConversationVO update(ConversationUpdateRequest request, Long userId);

    /**
     * 逻辑删除会话。
     */
    boolean delete(Long id, Long userId);

    /**
     * 取会话并校验归属：不存在 → 40400，非本人 → 40101。
     */
    Conversation getOwnedOrThrow(Long id, Long userId);
}
