package com.xiaohua.star.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.vo.MessageVO;

import java.util.List;

/**
 * 会话消息服务。见 knowledge/40-会话与消息管理.md。
 */
public interface ConversationMessageService extends IService<ConversationMessage> {

    /**
     * 拉取历史消息：取最近一页（seq 倒序），页内反转成正序返回。
     */
    IPage<MessageVO> listPage(Long conversationId, Long userId, int current, int pageSize);

    /**
     * 截断重发：删除 seq &gt;= fromSeq 的全部消息（真删），并回退 messageCount。返回删除条数。
     */
    int truncateFrom(Long conversationId, Long userId, Integer fromSeq);

    /**
     * 追加一条消息。内部对会话行加锁生成 seq，并同步 messageCount / lastMessageTime。
     *
     * <p>必须跨 bean 调用（走 Spring 事务代理），不要 {@code this.appendMessage(...)} 自调用。
     */
    ConversationMessage appendMessage(Long conversationId, Long userId, String role,
                                      String content, Integer tokenCount);

    /**
     * 取该会话全部 promptVisible=1 的消息，按 seq 升序。用于重建 prompt（最小闭环不裁剪）。
     */
    List<ConversationMessage> listPromptVisibleAsc(Long conversationId);

    /**
     * 消息实体 → MessageVO（含 toolCallsJson 解析）。供非流式对话等复用。
     */
    MessageVO toVO(ConversationMessage message);
}
