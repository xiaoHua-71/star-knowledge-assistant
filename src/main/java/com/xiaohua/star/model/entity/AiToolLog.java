package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具调用日志（{@code ai_tool_log}，M12）。见 knowledge/10 3.4、knowledge/50。
 *
 * <p>每次**真实执行**与每次**结果重放**各一行（{@code replayed} 区分）。与 {@code agent_step} 的
 * 关键差异：这里的 {@code idempotencyKey} 是<b>普通索引</b>，所以重放能留下第二条日志；
 * 而 {@code agent_step} 上是唯一索引（防线）。
 *
 * <p><b>没有 {@code isDelete}</b>。
 */
@TableName(value = "ai_tool_log")
@Data
public class AiToolLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** 对应的 agent_step.id */
    private Long stepId;

    private Long userId;

    private String toolName;

    /** LOCAL / LOCAL_QUERY / INTERNAL / ...（来自 @ToolMeta.source） */
    private String source;

    /** 0/1 */
    private Integer sideEffect;

    private String argumentsJson;

    private String resultJson;

    /** 0/1 */
    private Integer success;

    private String errorMsg;

    private Long elapsedMs;

    /** 0/1；1 = 结果来自中断恢复时的重放，没有重新执行 */
    private Integer replayed;

    private String idempotencyKey;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
