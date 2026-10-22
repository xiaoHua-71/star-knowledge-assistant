package com.xiaohua.star.agent;

import com.xiaohua.star.config.AgentProperties;
import com.xiaohua.star.model.entity.AgentRun;
import com.xiaohua.star.model.entity.AgentStep;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.enums.AgentStepStatusEnum;
import com.xiaohua.star.model.enums.AgentStepTypeEnum;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时封存（M13）。见 knowledge/12 坑 5：中断后一直不恢复的运行会让会话「有问无答」。
 *
 * <p>定时把超过 {@code ai.agent.suspend-retention-ms}（默认 24h）仍处于 {@code SUSPENDED}
 * 的运行置为 {@code CANCELLED}；若有足够长的半截输出，落一条 {@code interrupted=1} 的助手消息，
 * 让界面能看到「当时回答到一半就停了」。
 *
 * <p>两条边界：
 * <ul>
 *   <li>半截文本短于 {@value #MIN_PARTIAL_CHARS} 字符时不落库（knowledge/11：碎片会干扰后续模型）。</li>
 *   <li>运行的用户消息已不是会话最新一条时不落库（否则会把半截回答追加到后续对话末尾，顺序错乱）。</li>
 * </ul>
 */
@Slf4j
@Component
public class RunTimeoutScanner {

    /** 与 knowledge/11 的约定一致：太短的半截回答丢弃。 */
    static final int MIN_PARTIAL_CHARS = 10;

    /** 单次扫描最多处理多少条，避免一次占用数据库太久。 */
    private static final int BATCH_SIZE = 100;

    @Resource
    private AgentStateService agentStateService;

    @Resource
    private ConversationMessageService conversationMessageService;

    @Resource
    private AgentProperties agentProperties;

    @Scheduled(fixedDelayString = "${ai.agent.seal-scan-interval-ms:600000}",
            initialDelayString = "${ai.agent.seal-initial-delay-ms:60000}")
    public void scan() {
        long retentionMs = agentProperties.getSuspendRetentionMs();
        if (retentionMs <= 0) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(retentionMs));
            List<AgentRun> expired = agentStateService.listSuspendedBefore(cutoff, BATCH_SIZE);
            for (AgentRun run : expired) {
                seal(run);
            }
        } catch (Exception e) {
            // 维护任务失败不该影响主流程，下次继续
            log.error("超时封存扫描失败", e);
        }
    }

    private void seal(AgentRun run) {
        try {
            // 条件更新 SUSPENDED → CANCELLED：与用户「继续」并发时只有一个能成功
            if (!agentStateService.cancelRunIfSuspended(run.getId())) {
                return;
            }
            String partial = interruptedText(run.getId());
            if (partial == null || partial.trim().length() < MIN_PARTIAL_CHARS) {
                log.info("超时封存: runId={}（无足够长的半截回答）", run.getId());
                return;
            }
            if (!inputMessageIsLatest(run)) {
                log.info("超时封存: runId={}（用户消息已不是会话最新一条，不落助手消息）", run.getId());
                return;
            }
            conversationMessageService.appendMessage(run.getConversationId(), run.getUserId(),
                    ConversationMessage.ROLE_ASSISTANT, partial, null, run.getId(), true);
            log.info("超时封存: runId={}，已落 interrupted 助手消息（{} 字符）",
                    run.getId(), partial.length());
        } catch (Exception e) {
            // 单个运行失败不阻断其余运行
            log.warn("超时封存失败: runId={}", run.getId(), e);
        }
    }

    /** 最后一条被中断的 LLM 步里的半截文本。 */
    private String interruptedText(long runId) {
        String text = null;
        for (AgentStep step : agentStateService.listSteps(runId)) {
            if (AgentStepTypeEnum.LLM_CALL.name().equals(step.getType())
                    && AgentStepStatusEnum.INTERRUPTED.name().equals(step.getStatus())) {
                text = ResumePlanner.textOf(step.getResponseJson());
            }
        }
        return text;
    }

    /** 与 resume 的护栏一致：该运行的用户消息必须是会话最新一条，否则半截回答会插到后面。 */
    private boolean inputMessageIsLatest(AgentRun run) {
        List<ConversationMessage> messages =
                conversationMessageService.listPromptVisibleAsc(run.getConversationId());
        if (messages.isEmpty()) {
            return false;
        }
        Long latestId = messages.get(messages.size() - 1).getId();
        return latestId != null && latestId.equals(run.getInputMessageId());
    }
}
