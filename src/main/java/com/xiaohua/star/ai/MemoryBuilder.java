package com.xiaohua.star.ai;

import com.xiaohua.star.config.MemoryProperties;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.service.ConversationMessageService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话记忆重建。见 knowledge/11-对话记忆.md。
 *
 * <p><b>不用官方的 {@code ChatMemory} / {@code ChatMemoryStore}</b>（理由见 knowledge/11 ③：
 * 消息本就是业务数据，双写会不一致）。这里在<b>每次 LLM 调用前</b>从 {@code conversation_message}
 * 重建一次 prompt：一次 {@code SELECT} 的成本可以忽略，换来的是永不失效（内存态容器则要处理
 * 用户删消息、截断重发、多标签页并发等各种失效场景）。
 *
 * <p>只在预算内保留<b>最近</b>的若干条历史（丢最早的），并遵守四条组装规则里属于本模块的三条：
 * ① 系统消息永远保留；④ 最后一条是当前用户消息（因为用户消息在调模型前已先落库，它天然是
 * seq 最大的一条）。规则②③（工具调用请求与结果成对）在本模块无涉及——工具中间消息不落
 * {@code conversation_message}，本模块的历史里不存在它们。
 */
@Slf4j
@Component
public class MemoryBuilder {

    @Resource
    private ConversationMessageService conversationMessageService;

    @Resource
    private MemoryProperties memoryProperties;

    /**
     * 重建送给模型的 prompt：{@code [SystemMessage, ...预算内的历史..., 最新一条]}。
     *
     * <p>调用时机是「用户消息已落库之后」，所以取到的历史里已包含当前用户消息，无需另行追加。
     */
    public List<ChatMessage> build(Long conversationId, String systemPrompt) {
        return build(conversationId, systemPrompt, null);
    }

    /**
     * 带检索上下文的重建（RAG 用，见 knowledge/23-混合检索与融合.md）。
     *
     * <p>检索到的资料<b>并入同一条 system 消息</b>（而非另起一条 SystemMessage）：一是避免不同
     * provider 对多条 system 的处理差异，二是让 context 的 token 自动计入 {@link MemoryProperties#historyBudget}
     * ——预算不够时优先挤历史，不会让资料把上下文撑爆。
     */
    public List<ChatMessage> build(Long conversationId, String systemPrompt, String retrievedContext) {
        String fullSystem = composeSystem(systemPrompt, retrievedContext);
        List<ConversationMessage> asc = conversationMessageService.listPromptVisibleAsc(conversationId);
        double coef = memoryProperties.getTokenPerChar();
        int budget = memoryProperties.historyBudget(estimateTokens(fullSystem, coef));
        List<ChatMessage> messages = assemble(asc, fullSystem, budget, coef);
        log.debug("记忆重建: conversationId={}, 历史共 {} 条, 预算 {} token, 含检索资料={}, 实发 {} 条",
                conversationId, asc.size(), budget, !fullSystem.equals(systemPrompt), messages.size() - 1);
        return messages;
    }

    /** 单条文本的 token 估算（用配置的系数）。用于落库时记 {@code conversation_message.tokenCount}。 */
    public int estimate(String text) {
        return estimateTokens(text, memoryProperties.getTokenPerChar());
    }

    // ---------------- 纯逻辑（便于单测，不依赖 Spring） ----------------

    /** {@code tokens ≈ 字符数 × 系数}，向上取整。这是近似，不是真实 tokenizer（见 knowledge/11 ④）。 */
    static int estimateTokens(String text, double tokenPerChar) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() * tokenPerChar);
    }

    /** 把检索上下文并入系统提示词，合成同一条 system 消息。空白上下文原样返回系统提示词。 */
    static String composeSystem(String systemPrompt, String retrievedContext) {
        if (retrievedContext == null || retrievedContext.isBlank()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + retrievedContext;
    }

    /**
     * 从升序历史里挑出预算内的<b>最近</b>一段（连续后缀），组装成 prompt。
     *
     * @param asc         按 seq 升序的历史消息
     * @param systemPrompt 系统提示词（恒为结果首条）
     * @param budget      历史可用的 token 预算（已扣除系统提示词、输出预留、安全余量）
     * @param tokenPerChar 估算系数
     */
    static List<ChatMessage> assemble(List<ConversationMessage> asc, String systemPrompt,
                                      int budget, double tokenPerChar) {
        // 从最新往回累积，直到下一条装不下为止（保留连续的一段最近历史；不跳着放，避免断档）
        List<ConversationMessage> kept = new ArrayList<>();
        int acc = 0;
        for (int i = asc.size() - 1; i >= 0; i--) {
            ConversationMessage m = asc.get(i);
            int t = estimateTokens(m.getContent(), tokenPerChar);
            // 最新一条无论如何都保留（否则模型没问题可答），其余要装得下才留
            if (kept.isEmpty() || acc + t <= budget) {
                kept.add(m);
                acc += t;
            } else {
                break;
            }
        }
        Collections.reverse(kept);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt)); // 规则①：系统消息永远第一位
        for (ConversationMessage m : kept) {
            // ASSISTANT 只取 content；toolCallsJson 绝不进 prompt（见 knowledge/11 坑 5、knowledge/40 坑 4）
            messages.add(ConversationMessage.ROLE_ASSISTANT.equals(m.getRole())
                    ? AiMessage.from(m.getContent())
                    : UserMessage.from(m.getContent()));
        }
        return messages;
    }
}
