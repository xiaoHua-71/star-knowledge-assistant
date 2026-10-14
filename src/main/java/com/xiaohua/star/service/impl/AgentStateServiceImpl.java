package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xiaohua.star.mapper.AgentEventMapper;
import com.xiaohua.star.mapper.AgentRunMapper;
import com.xiaohua.star.mapper.AgentStepMapper;
import com.xiaohua.star.model.entity.AgentEvent;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.enums.AgentEventTypeEnum;
import com.xiaohua.star.model.enums.AgentRunStatusEnum;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;
import com.xiaohua.star.service.AgentStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 状态落库实现（M8）。每个方法一个短事务，见 {@link AgentStateService} 的类注释。
 *
 * <p>全部走 {@code BaseMapper} + {@code LambdaUpdateWrapper}，无手写 SQL。更新用
 * {@code updateById}（MyBatis-Plus 默认 NOT_NULL 策略：只更新非 null 字段，其余保持原值）。
 */
@Slf4j
@Service
public class AgentStateServiceImpl implements AgentStateService {

    /** 启动扫描时给「结果未知的副作用步骤」写的 errorMsg。 */
    static final String SERVER_RESTART_STEP_MSG = "服务重启，执行结果未知，请人工确认";

    @Resource
    private AgentRunMapper agentRunMapper;

    @Resource
    private AgentStepMapper agentStepMapper;

    @Resource
    private AgentEventMapper agentEventMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createRun(Long conversationId, Long userId, Long inputMessageId, int maxRounds) {
        AgentRun run = new AgentRun();
        run.setConversationId(conversationId);
        run.setUserId(userId);
        run.setStatus(AgentRunStatusEnum.PENDING.name());
        run.setStepNo(0);
        run.setMaxRounds(maxRounds);
        run.setPlanVersion(0);
        run.setInputMessageId(inputMessageId);
        run.setTokenIn(0);
        run.setTokenOut(0);
        agentRunMapper.insert(run);
        return run.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRunning(long runId) {
        AgentRun update = new AgentRun();
        update.setId(runId);
        update.setStatus(AgentRunStatusEnum.RUNNING.name());
        update.setStartTime(LocalDateTime.now());
        agentRunMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long appendEvent(long runId, Long conversationId, AgentEventTypeEnum type, String dataJson) {
        AgentEvent event = new AgentEvent();
        event.setRunId(runId);
        event.setConversationId(conversationId);
        event.setEventType(type.code());
        event.setDataJson(dataJson);
        agentEventMapper.insert(event);
        return event.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long beginLlmStep(long runId, int stepNo, int seq, String requestJson) {
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setStepNo(stepNo);
        step.setSeq(seq);
        step.setType(AgentStepTypeEnum.LLM_CALL.name());
        step.setStatus(AgentStepStatusEnum.PENDING.name());
        step.setRequestJson(requestJson);
        step.setReplayed(0);
        step.setSideEffect(0);
        step.setRequireApproval(0);
        step.setStartTime(LocalDateTime.now());
        agentStepMapper.insert(step);
        return step.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishLlmStep(long stepId, String responseJson, long elapsedMs,
                              AgentStepStatusEnum status, String errorMsg) {
        AgentStep update = new AgentStep();
        update.setId(stepId);
        update.setStatus(status.name());
        update.setResponseJson(responseJson);
        update.setElapsedMs(elapsedMs);
        update.setErrorMsg(errorMsg);
        update.setEndTime(LocalDateTime.now());
        agentStepMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long beginToolStep(long runId, int stepNo, int seq, String toolCallId, String toolName,
                              String argumentsJson, boolean sideEffect, boolean requireApproval) {
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setStepNo(stepNo);
        step.setSeq(seq);
        step.setType(AgentStepTypeEnum.TOOL_CALL.name());
        step.setStatus(AgentStepStatusEnum.PENDING.name());
        step.setToolCallId(toolCallId);
        step.setToolName(toolName);
        step.setArgumentsJson(argumentsJson);
        step.setSideEffect(sideEffect ? 1 : 0);
        step.setRequireApproval(requireApproval ? 1 : 0);
        step.setReplayed(0);
        step.setStartTime(LocalDateTime.now());
        agentStepMapper.insert(step);
        return step.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishToolStep(long stepId, String toolResultJson, long elapsedMs,
                               AgentStepStatusEnum status, String errorMsg) {
        AgentStep update = new AgentStep();
        update.setId(stepId);
        update.setStatus(status.name());
        update.setToolResultJson(toolResultJson);
        update.setElapsedMs(elapsedMs);
        update.setErrorMsg(errorMsg);
        update.setEndTime(LocalDateTime.now());
        agentStepMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finalizeRun(long runId, Long outputMessageId, int tokenIn, int tokenOut, int stepNo) {
        AgentRun update = new AgentRun();
        update.setId(runId);
        update.setStatus(AgentRunStatusEnum.COMPLETED.name());
        update.setOutputMessageId(outputMessageId);
        update.setTokenIn(tokenIn);
        update.setTokenOut(tokenOut);
        update.setStepNo(stepNo);
        update.setEndTime(LocalDateTime.now());
        agentRunMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void failRun(long runId, String errorMsg) {
        AgentRun update = new AgentRun();
        update.setId(runId);
        update.setStatus(AgentRunStatusEnum.FAILED.name());
        update.setErrorMsg(errorMsg);
        update.setEndTime(LocalDateTime.now());
        agentRunMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int[] scanZombieRuns() {
        // ① 非终态运行 → 挂起（服务重启）
        int runs = agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .set(AgentRun::getStatus, AgentRunStatusEnum.SUSPENDED.name())
                .set(AgentRun::getInterruptType, InterruptTypeEnum.SERVER_RESTART.name())
                .in(AgentRun::getStatus, AgentRunStatusEnum.PENDING.name(), AgentRunStatusEnum.RUNNING.name()));

        // ② 「执行结果未知」的副作用步骤 → FAILED（不能让恢复逻辑自作主张重跑）
        List<String> stuckStatuses = List.of(
                AgentStepStatusEnum.PENDING.name(), AgentStepStatusEnum.RUNNING.name());
        int steps = agentStepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .set(AgentStep::getStatus, AgentStepStatusEnum.FAILED.name())
                .set(AgentStep::getErrorMsg, SERVER_RESTART_STEP_MSG)
                .in(AgentStep::getStatus, stuckStatuses)
                .eq(AgentStep::getSideEffect, 1)
                .isNull(AgentStep::getToolResultJson));

        return new int[]{runs, steps};
    }

    @Override
    public boolean hasActiveRun(Long conversationId) {
        Long count = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getConversationId, conversationId)
                .in(AgentRun::getStatus, AgentRunStatusEnum.PENDING.name(), AgentRunStatusEnum.RUNNING.name()));
        return count != null && count > 0;
    }
}
