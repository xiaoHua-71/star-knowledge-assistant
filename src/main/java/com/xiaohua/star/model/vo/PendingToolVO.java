package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 待人工确认的工具（{@code AgentRunVO.pendingTool}，M10）。契约见 frontend/40。
 *
 * <p>只在运行状态为 {@code WAITING_APPROVAL} 时非空。前端必须把 {@link #arguments}
 * <b>原样展示</b>——那是用户判断「要不要点同意」的全部依据。
 */
@Data
public class PendingToolVO implements Serializable {

    /** 对应的 {@code agent_step.id} */
    private Long stepId;

    /** 工具英文名（模型看到的标识符） */
    private String toolName;

    /** 中文名，给用户看（不要直接展示 toolName） */
    private String toolDisplayName;

    /** 工具的自然语言说明，可直接展示 */
    private String toolDescription;

    /** 待执行的参数（已解析成对象） */
    private Object arguments;

    /**
     * 风险提示。非空时前端要显著展示，例如：
     * 「上次执行结果未知（服务重启），该操作可能已经发生，请先核实」。
     */
    private String warning;

    private static final long serialVersionUID = 1L;
}
