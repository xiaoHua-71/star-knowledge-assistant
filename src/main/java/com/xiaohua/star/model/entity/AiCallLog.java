package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型调用日志（{@code ai_call_log}，M12）。见 knowledge/10 3.4、knowledge/50。
 *
 * <p>一次 LLM 调用一行。它与 {@code agent_step} 的职责不同：step 是状态机的状态，
 * 这张表是**只追加的日志**，用于成本、耗时、首 token 延迟分析。
 *
 * <p><b>没有 {@code isDelete}</b>。{@code costYuan} 用 {@link BigDecimal}（金额不能用浮点数）。
 */
@TableName(value = "ai_call_log")
@Data
public class AiCallLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long runId;

    /** 产出哪条消息（当前版本留空） */
    private Long messageId;

    private Long userId;

    /** CHAT / QUERY_REWRITE / TOOL_PLAN / TITLE_GEN */
    private String scene;

    private String provider;

    private String modelName;

    /** 0/1 */
    private Integer streaming;

    /** 请求快照（落库前按 ai.observability.max-snapshot-chars 截断） */
    private String requestJson;

    /** 响应快照 */
    private String responseJson;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 成本（元）；价格未配置时为 null，而不是 0（避免被误读为免费） */
    private BigDecimal costYuan;

    /** 首 token 延迟（仅流式） */
    private Long firstTokenMs;

    private Long elapsedMs;

    /** 0/1 */
    private Integer success;

    private String errorMsg;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
