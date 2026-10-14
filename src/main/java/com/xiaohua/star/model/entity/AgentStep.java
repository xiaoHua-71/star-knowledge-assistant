package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 执行步骤（状态机的核心）：审计 + 重放 + 幂等防线。见 knowledge/10 3.3、knowledge/12。
 *
 * <p>工具结果<b>回填到 TOOL_CALL 同一行</b>（{@code toolResultJson}），不另起 TOOL_RESULT 行。
 * {@code uk_runId_seq} 决定重放顺序；{@code uk_idempotencyKey} 是副作用工具的唯一防线（M9 起用）。
 *
 * <p><b>没有 {@code isDelete}</b>。{@code sideEffect} / {@code requireApproval} 用 Integer（0/1）。
 */
@TableName(value = "agent_step")
@Data
public class AgentStep implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** 第几轮 */
    private Integer stepNo;

    /** 运行内自增序号，决定重放顺序 */
    private Integer seq;

    /** {@link com.xiaohua.star.model.enums.AgentStepTypeEnum} 的 name */
    private String type;

    /** {@link com.xiaohua.star.model.enums.AgentStepStatusEnum} 的 name */
    private String status;

    /** 模型给的调用 id */
    private String toolCallId;

    private String toolName;

    /** 工具入参（原始 JSON） */
    private String argumentsJson;

    /** 工具结果（重放时直接用） */
    private String toolResultJson;

    /** 副作用工具的幂等键 */
    private String idempotencyKey;

    /** 是否改变系统状态，0/1 */
    private Integer sideEffect;

    /** 是否需要人工确认，0/1 */
    private Integer requireApproval;

    /** 本次结果是否来自重放，0/1 */
    private Integer replayed;

    /** LLM 调用入参快照 */
    private String requestJson;

    /** LLM 响应快照 */
    private String responseJson;

    private String errorMsg;

    private Long elapsedMs;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
