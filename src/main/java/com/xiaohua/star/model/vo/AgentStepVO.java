package com.xiaohua.star.model.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一次 Agent 运行中的单个执行步骤（前端契约见 frontend/40-agent-中断与恢复.md）。
 *
 * <p>{@code replayed=true} 表示这一步的结果是从断点重放读出来的，没有重新执行。
 */
@Data
public class AgentStepVO implements Serializable {

    private Long id;

    /** 运行内执行顺序 */
    private Integer seq;

    /** LLM_CALL / TOOL_CALL */
    private String type;

    /** PENDING/RUNNING/SUCCEEDED/FAILED/INTERRUPTED */
    private String status;

    /** 工具名（LLM 步为 null） */
    private String toolName;

    /** 工具入参（LLM 步为 null） */
    private Object arguments;

    /** 工具结果；LLM 步为响应快照 {text, toolCalls?} */
    private Object result;

    /** 结果是否来自重放 */
    private boolean replayed;

    private String errorMsg;

    /** 耗时（毫秒）。用 Integer 而非 Long：契约要求 number，全局 Long→String 会把它变成字符串。 */
    private Integer elapsedMs;

    private LocalDateTime startTime;

    private static final long serialVersionUID = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 执行步骤 → 前端视图。工具结果可能是非 JSON 的纯文本（如「未执行」），包成 {text} 返回。 */
    public static AgentStepVO from(AgentStep step) {
        if (step == null) {
            return null;
        }
        AgentStepVO vo = new AgentStepVO();
        vo.setId(step.getId());
        vo.setSeq(step.getSeq());
        vo.setType(step.getType());
        vo.setStatus(step.getStatus());
        vo.setToolName(step.getToolName());
        vo.setReplayed(step.getReplayed() != null && step.getReplayed() == 1);
        vo.setErrorMsg(step.getErrorMsg());
        vo.setElapsedMs(step.getElapsedMs() == null ? null : step.getElapsedMs().intValue());
        vo.setStartTime(step.getStartTime());

        if (AgentStepTypeEnum.TOOL_CALL.name().equals(step.getType())) {
            vo.setArguments(parseJsonOrText(step.getArgumentsJson()));
            vo.setResult(parseJsonOrText(step.getToolResultJson()));
        } else {
            vo.setResult(parseJsonOrText(step.getResponseJson()));
        }
        return vo;
    }

    private static Object parseJsonOrText(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of("text", json);
        }
    }
}
