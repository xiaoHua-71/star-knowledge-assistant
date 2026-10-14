package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * SSE 事件（断线回放用）。见 knowledge/10 3.3、knowledge/31。
 *
 * <p>自增 {@code id} 直接当 SSE 的 {@code event id}——既是主键，又是天然有序、可比较的游标。
 * <b>逐 token 的 {@code message_delta} 不落这张表</b>（冗余；重连靠 {@code message_snapshot}）。
 *
 * <p><b>没有 {@code isDelete}</b>。
 */
@TableName(value = "agent_event")
@Data
public class AgentEvent implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long conversationId;

    /** {@link com.xiaohua.star.model.enums.AgentEventTypeEnum#code()} */
    private String eventType;

    /** 事件体（原始 JSON） */
    private String dataJson;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
