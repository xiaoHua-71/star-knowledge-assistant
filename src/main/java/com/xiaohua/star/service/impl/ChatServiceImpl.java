package com.xiaohua.star.service.impl;

import com.xiaohua.star.ai.MemoryBuilder;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.vo.MessageVO;
import com.xiaohua.star.service.ChatService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 非流式对话实现（简化路径）。见 knowledge/40 ③。
 *
 * <p>prompt 由 {@link MemoryBuilder} 重建（系统提示词 + 预算内的最近历史，见 knowledge/11）。
 *
 * <p><b>有意不加 {@code @Transactional}</b>：一次对话里夹着几十秒的模型调用，把它包进事务会让
 * 数据库连接和会话行锁被占住几十秒。所以是两个短事务（落用户消息、落助手消息），模型调用在事务之外。
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    /** 最大用户消息长度（字符）。见 frontend/30。 */
    static final int MAX_CONTENT_LEN = 4000;

    /**
     * 系统提示词常量。会作为 prompt 首条、永远保留（见 knowledge/11 规则①）；其 token 量也从
     * 记忆预算里先扣掉。将来要做提示词集中管理（模块 14）时从这里演进。
     */
    static final String SYSTEM_PROMPT = "你是「智能星」知识助手，用简洁、准确的中文回答用户问题；"
            + "不确定时如实说明，不要编造。";

    /** 对话模型可能不存在（没配 ai.chat.api-key 时 ChatModelConfig 不建 bean），用 ObjectProvider 容错。 */
    @Resource
    private ObjectProvider<ChatModel> chatModelProvider;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ConversationMessageService conversationMessageService;

    @Resource
    private MemoryBuilder memoryBuilder;

    @Override
    public MessageVO chat(ChatMessageRequest request, Long userId) {
        // 1. 参数校验（40000）
        if (request.getConversationId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
        String content = request.getContent();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "content 不能为空");
        }
        if (content.length() > MAX_CONTENT_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "content 超长（最多 " + MAX_CONTENT_LEN + " 字符）");
        }

        // 2. 校验会话归属（40400 / 40101）
        conversationService.getOwnedOrThrow(request.getConversationId(), userId);

        // 3. 取模型；缺则 50001，且在此之前不写任何数据（避免留下孤立的用户消息）
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "对话模型未配置（缺少 ai.chat.api-key / DEEPSEEK_API_KEY），无法对话");
        }

        // 4. 落用户消息（事务 #1）。tokenCount 记估算值。
        conversationMessageService.appendMessage(request.getConversationId(), userId,
                ConversationMessage.ROLE_USER, content, memoryBuilder.estimate(content));

        // 5. 重建 prompt（含系统消息 + 预算内的最近历史，且最后一条就是刚落的用户消息）
        List<ChatMessage> prompt = memoryBuilder.build(request.getConversationId(), SYSTEM_PROMPT);

        // 6. 调模型（事务之外）
        ChatResponse response;
        try {
            response = model.chat(prompt);
        } catch (Exception e) {
            log.warn("对话模型调用失败, conversationId={}", request.getConversationId(), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型调用失败：" + e.getMessage());
        }
        String answer = response.aiMessage().text();
        Integer tokens = null;
        if (response.metadata() != null && response.metadata().tokenUsage() != null) {
            tokens = response.metadata().tokenUsage().totalTokenCount();
        }

        // 7. 落助手消息（事务 #2）。助手侧记模型返回的真实 token（含思维链 token，比纯文本估算准）
        ConversationMessage assistant = conversationMessageService.appendMessage(
                request.getConversationId(), userId, ConversationMessage.ROLE_ASSISTANT, answer, tokens);

        // 8. 返回助手消息
        return conversationMessageService.toVO(assistant);
    }
}
