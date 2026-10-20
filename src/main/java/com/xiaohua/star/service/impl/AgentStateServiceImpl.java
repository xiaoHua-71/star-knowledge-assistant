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
import org.springframework.dao.DuplicateKeyException;
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

    /**
     * 幂等认领。**刻意不加 {@code @Transactional}**：单条 UPDATE 由连接自动提交；
     * 若包在事务里，撞唯一键抛出的 {@link DuplicateKeyException} 会把事务标成 rollback-only，
     * 再返回 {@code KEY_CONFLICT} 会在提交时变成 UnexpectedRollbackException。
     */
    @Override
    public ClaimResult claimToolStep(long stepId, String idempotencyKey) {
        try {
            int rows = agentStepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                    .eq(AgentStep::getId, stepId)
                    .isNull(AgentStep::getIdempotencyKey)
                    .isNull(AgentStep::getToolResultJson)
                    .in(AgentStep::getStatus,
                            AgentStepStatusEnum.PENDING.name(), AgentStepStatusEnum.FAILED.name())
                    .set(AgentStep::getIdempotencyKey, idempotencyKey)
                    .set(AgentStep::getStatus, AgentStepStatusEnum.RUNNING.name()));
            return rows > 0 ? ClaimResult.CLAIMED : ClaimResult.NOT_CLAIMABLE;
        } catch (DuplicateKeyException e) {
            // 同键已在另一行存在：由调用方查出来走三分支（重放 / 转人工，绝不重跑）
            return ClaimResult.KEY_CONFLICT;
        }
    }

    @Override
    public AgentStep findStepByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        List<AgentStep> list = agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getIdempotencyKey, idempotencyKey)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public AgentStep getStep(long stepId) {
        return agentStepMapper.selectById(stepId);
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
                .in(AgentRun::getStatus,
                        AgentRunStatusEnum.PENDING.name(),
                        AgentRunStatusEnum.RUNNING.name(),
                        AgentRunStatusEnum.WAITING_APPROVAL.name()));

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
    @Transactional(rollbackFor = Exception.class)
    public void suspendRun(long runId, InterruptTypeEnum interruptType) {
        agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .in(AgentRun::getStatus,
                        AgentRunStatusEnum.PENDING.name(),
                        AgentRunStatusEnum.RUNNING.name(),
                        AgentRunStatusEnum.WAITING_APPROVAL.name())
                .set(AgentRun::getStatus, AgentRunStatusEnum.SUSPENDED.name())
                .set(AgentRun::getInterruptType, interruptType.name()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markWaitingApproval(long runId, int stepNo) {
        int rows = agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .in(AgentRun::getStatus,
                        AgentRunStatusEnum.PENDING.name(),
                        AgentRunStatusEnum.RUNNING.name(),
                        AgentRunStatusEnum.SUSPENDED.name())
                .set(AgentRun::getStatus, AgentRunStatusEnum.WAITING_APPROVAL.name())
                .set(AgentRun::getInterruptType, InterruptTypeEnum.APPROVAL_REQUIRED.name())
                .set(AgentRun::getStepNo, stepNo));
        return rows > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean approveRun(long runId) {
        int rows = agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getStatus, AgentRunStatusEnum.WAITING_APPROVAL.name())
                .set(AgentRun::getStatus, AgentRunStatusEnum.RUNNING.name())
                .set(AgentRun::getInterruptType, null));
        return rows > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean denyApproval(long runId) {
        int rows = agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getStatus, AgentRunStatusEnum.WAITING_APPROVAL.name())
                .set(AgentRun::getStatus, AgentRunStatusEnum.SUSPENDED.name())
                .set(AgentRun::getInterruptType, null));
        return rows > 0;
    }

    @Override
    public AgentStep findPendingApprovalStep(long runId) {
        // 不限定 requireApproval：自动执行的副作用工具撞上「结果未知」时也会转人工，
        // 那一行的 requireApproval 可能是 0。
        List<AgentStep> list = agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, runId)
                .eq(AgentStep::getType, AgentStepTypeEnum.TOOL_CALL.name())
                .eq(AgentStep::getSideEffect, 1)
                .isNull(AgentStep::getToolResultJson)
                .orderByDesc(AgentStep::getSeq)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean resumeRun(long runId) {
        int rows = agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getStatus, AgentRunStatusEnum.SUSPENDED.name())
                .set(AgentRun::getStatus, AgentRunStatusEnum.RUNNING.name())
                .set(AgentRun::getInterruptType, null));
        return rows > 0;
    }

    @Override
    public AgentRun getRun(long runId) {
        return agentRunMapper.selectById(runId);
    }

    @Override
    public List<AgentStep> listSteps(long runId) {
        return agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, runId)
                .orderByAsc(AgentStep::getSeq));
    }

    @Override
    public List<AgentEvent> listEventsAfter(long runId, long lastEventId) {
        return agentEventMapper.selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getRunId, runId)
                .gt(AgentEvent::getId, lastEventId)
                .orderByAsc(AgentEvent::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReplayed(long stepId) {
        agentStepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getId, stepId)
                .set(AgentStep::getReplayed, 1));
    }

    @Override
    public Long findActiveRunId(Long conversationId) {
        // WAITING_APPROVAL 也算「未结束」：用户不等确认直接发新消息时，界面服务要把它收敛掉
        List<AgentRun> list = agentRunMapper.selectList(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getConversationId, conversationId)
                .in(AgentRun::getStatus,
                        AgentRunStatusEnum.PENDING.name(),
                        AgentRunStatusEnum.RUNNING.name(),
                        AgentRunStatusEnum.WAITING_APPROVAL.name())
                .orderByDesc(AgentRun::getId)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0).getId();
    }

    @Override
    public boolean hasActiveRun(Long conversationId) {
        Long count = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getConversationId, conversationId)
                .in(AgentRun::getStatus,
                        AgentRunStatusEnum.PENDING.name(),
                        AgentRunStatusEnum.RUNNING.name(),
                        AgentRunStatusEnum.WAITING_APPROVAL.name()));
        return count != null && count > 0;
    }
}
