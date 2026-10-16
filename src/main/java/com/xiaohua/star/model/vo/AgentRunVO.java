package com.xiaohua.star.model.vo;

import com.xiaohua.star.model.entity.AgentRun;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次 Agent 运行的状态视图（前端契约见 frontend/40-agent-中断与恢复.md）。
 *
 * <p>{@code pendingTool} 在 M9 恒为 {@code null}——人工确认（{@code WAITING_APPROVAL}）属 M10。
 */
@Data
public class AgentRunVO implements Serializable {

    private Long id;

    private Long conversationId;

    /** PENDING/RUNNING/SUSPENDED/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED */
    private String status;

    /** USER_STOP/NEW_MESSAGE/SERVER_RESTART/APPROVAL_REQUIRED；未挂起为 null */
    private String interruptType;

    private Integer stepNo;

    private Integer maxRounds;

    /** 触发本次运行的用户消息 id */
    private Long inputMessageId;

    /** 最终助手消息 id（仅 COMPLETED 时非空） */
    private Long outputMessageId;

    /** 待人工确认的工具（M9 恒 null，属 M10） */
    private Object pendingTool;

    private String errorMsg;

    private Integer tokenIn;

    private Integer tokenOut;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;

    public static AgentRunVO from(AgentRun run) {
        if (run == null) {
            return null;
        }
        AgentRunVO vo = new AgentRunVO();
        vo.setId(run.getId());
        vo.setConversationId(run.getConversationId());
        vo.setStatus(run.getStatus());
        vo.setInterruptType(run.getInterruptType());
        vo.setStepNo(run.getStepNo());
        vo.setMaxRounds(run.getMaxRounds());
        vo.setInputMessageId(run.getInputMessageId());
        vo.setOutputMessageId(run.getOutputMessageId());
        vo.setErrorMsg(run.getErrorMsg());
        vo.setTokenIn(run.getTokenIn());
        vo.setTokenOut(run.getTokenOut());
        vo.setStartTime(run.getStartTime());
        vo.setEndTime(run.getEndTime());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }
}
