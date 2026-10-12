package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话消息。
 *
 * <p><b>⚠️ 这张表【刻意没有】{@code isDelete} 字段，是条绊线，别加。</b>
 * 它要「真删」：截断重发时把 {@code (conversationId, seq)} 腾出来重新占用；一旦加了
 * {@code isDelete}，全局逻辑删就会接管——删除变成 {@code UPDATE isDelete=1}，旧行仍占着
 * {@code uk_conversationId_seq}，重发立刻撞唯一键。全局逻辑删只作用于【自己声明了 isDelete
 * 的实体】，所以本实体没有它就不受影响，{@code delete()} 是真的 {@code DELETE}。
 *
 * <p>顺序一律用 {@code seq}（从 1 开始），不用 {@code createTime}——毫秒精度不足以区分
 * 助手/用户消息的写入先后。见 knowledge/40。
 */
@TableName(value = "conversation_message")
@Data
public class ConversationMessage implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /**
     * 属于哪次 Agent 运行；M4 为 null（尚无 agent_run）
     */
    private Long runId;

    /**
     * 会话内序号，从 1 开始，决定顺序（权威顺序）
     */
    private Integer seq;

    /**
     * USER / ASSISTANT（本模块只写这两种）
     */
    private String role;

    private String content;

    private String toolCallId;

    /**
     * 工具调用请求的原始 JSON，仅供展示/审计，<b>绝不进 prompt</b>（见 knowledge/11 坑 5、knowledge/40 坑 4）
     */
    private String toolCallsJson;

    /**
     * 是否参与 prompt 重建：0 仅展示
     */
    private Integer promptVisible;

    /**
     * 是否被中断截断，0/1
     */
    private Integer interrupted;

    private Integer tokenCount;

    private LocalDateTime createTime;

    /** 角色：用户消息 */
    public static final String ROLE_USER = "USER";

    /** 角色：助手消息 */
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    private static final long serialVersionUID = 1L;
}
