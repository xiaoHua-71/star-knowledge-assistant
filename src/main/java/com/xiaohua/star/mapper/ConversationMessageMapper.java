package com.xiaohua.star.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.star.model.entity.ConversationMessage;

/**
 * 会话消息 Mapper。无 {@code @Mapper}（全局扫描）。
 *
 * <p>注意：本实体没有 {@code isDelete}，故这里的 {@code delete(...)} 是真的物理删除，
 * {@code select*} 也不会被追加 {@code isDelete = 0}。见 knowledge/40。
 */
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {
}
