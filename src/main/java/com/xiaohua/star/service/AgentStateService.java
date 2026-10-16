package com.xiaohua.star.service;

import com.xiaohua.star.model.entity.AgentEvent;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.enums.AgentEventTypeEnum;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.InterruptTypeEnum;

import java.util.List;

/**
 * Agent 运行状态的持久化门面（M8）。见 knowledge/12-中断与恢复的状态机.md。
 *
 * <p><b>每个方法各自一个短事务，独立提交</b>——绝不把 {@code @Transactional} 加在 Agent 主循环上
 * （knowledge/12 坑 1：长事务会耗尽连接池，且回滚会抹掉「副作用已发生」的证据）。
 * 循环在事务之外调用模型与工具，只在**步骤边界**落库。
 *
 * <p>写库顺序的唯一硬约束：<b>工具的意图行必须先提交，再执行工具</b>——反了的话，工具已真实执行
 * 而意图未落库，崩溃后就永远不知道副作用发生过。
 */
public interface AgentStateService {

    /** 创建运行（PENDING），返回自增 id（即 {@code runId}）。 */
    long createRun(Long conversationId, Long userId, Long inputMessageId, int maxRounds);

    /** 开始执行：PENDING → RUNNING，记 startTime。 */
    void markRunning(long runId);

    /** 追加一个事件，返回自增 id（**直接用作 SSE 的 event id**）。 */
    long appendEvent(long runId, Long conversationId, AgentEventTypeEnum type, String dataJson);

    /** 落一行 LLM 步骤（PENDING），返回 stepId。调用模型前先落库。 */
    long beginLlmStep(long runId, int stepNo, int seq, String requestJson);

    /** 回填 LLM 步骤结果。 */
    void finishLlmStep(long stepId, String responseJson, long elapsedMs,
                       AgentStepStatusEnum status, String errorMsg);

    /** 落一行工具意图（PENDING），**返回后调用方才执行工具**。 */
    long beginToolStep(long runId, int stepNo, int seq, String toolCallId, String toolName,
                       String argumentsJson, boolean sideEffect, boolean requireApproval);

    /** 回填工具结果到同一行。 */
    void finishToolStep(long stepId, String toolResultJson, long elapsedMs,
                        AgentStepStatusEnum status, String errorMsg);

    /** 运行正常结束：COMPLETED + outputMessageId + token 累计 + endTime。 */
    void finalizeRun(long runId, Long outputMessageId, int tokenIn, int tokenOut, int stepNo);

    /** 运行失败：FAILED + errorMsg + endTime。 */
    void failRun(long runId, String errorMsg);

    /** 运行挂起：RUNNING/PENDING → SUSPENDED + interruptType（M9）。 */
    void suspendRun(long runId, InterruptTypeEnum interruptType);

    /**
     * 运行续跑：条件更新 {@code SUSPENDED → RUNNING} 且清空 {@code interruptType}（M9）。
     *
     * @return true = 本次真的翻转过（防「双击继续」「并发续跑」重复起循环）
     */
    boolean resumeRun(long runId);

    /** 取运行（不存在返回 {@code null}）。 */
    AgentRun getRun(long runId);

    /** 运行的全部步骤，按 {@code seq} 升序（重放顺序）。 */
    List<AgentStep> listSteps(long runId);

    /** 该运行 {@code id > lastEventId} 的事件，按 {@code id} 升序（断线补发用）。 */
    List<AgentEvent> listEventsAfter(long runId, long lastEventId);

    /** 把某步骤标为「结果来自重放」（续跑时重放已完成工具）。 */
    void markReplayed(long stepId);

    /**
     * 该会话当前正在跑的运行 id（PENDING/RUNNING），无则 {@code null}。
     * 用于「用户在运行中又发新消息」时给旧运行置 {@code NEW_MESSAGE} 中断标志。
     */
    Long findActiveRunId(Long conversationId);

    /**
     * 启动扫描（knowledge/12 坑 6）：
     * ① 非终态运行 → SUSPENDED(SERVER_RESTART)；
     * ② 「执行结果未知」的副作用步骤 → FAILED。
     *
     * @return {@code [被挂起的运行数, 被置失败的步骤数]}
     */
    int[] scanZombieRuns();

    /** 该会话是否有未结束的运行（PENDING/RUNNING）。用于「截断重发」等操作的前置校验。 */
    boolean hasActiveRun(Long conversationId);
}
