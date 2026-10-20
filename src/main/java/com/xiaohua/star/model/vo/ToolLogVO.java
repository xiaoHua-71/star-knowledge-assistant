package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具调用日志视图（{@code GET /tools/logs}，M12）。契约见 frontend/60。
 *
 * <p>{@code id} / {@code runId} 按全局约定由 {@code JsonConfig} 序列化成字符串；
 * {@code elapsedMs} 用 Integer，保持 JSON 数字。
 */
@Data
public class ToolLogVO implements Serializable {

    private Long id;

    private Long runId;

    private String toolName;

    /** 中文名（工具已下线时兜底为 toolName） */
    private String displayName;

    private Boolean sideEffect;

    private Boolean success;

    /** 解析后的参数对象；原始 JSON 损坏时为 null */
    private Object arguments;

    /** 解析后的结果；非 JSON 文本包成 {@code {"text": ...}} */
    private Object result;

    private String errorMsg;

    private Integer elapsedMs;

    /** true = 结果来自中断恢复时的重放，没有重新执行 */
    private Boolean replayed;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
