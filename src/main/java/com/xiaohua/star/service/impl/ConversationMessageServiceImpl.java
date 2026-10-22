package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.ConversationMapper;
import com.xiaohua.star.mapper.ConversationMessageMapper;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.entity.ConversationMessage;
import com.xiaohua.star.model.vo.MessageVO;
import com.xiaohua.star.service.AgentStateService;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会话消息服务实现。见 knowledge/40-会话与消息管理.md。
 *
 * <p>本类里 {@code appendMessage} / {@code truncateFrom} 用<b>会话行锁 + 短事务</b>保证 seq
 * 不撞号、{@code messageCount} 不漂移。两条铁律：① 事务里<b>不得</b>有远程调用（模型调用在
 * {@code ChatServiceImpl}，事务之外）；② 追加消息必须跨 bean 调用以走 Spring 事务代理。
 */
@Slf4j
@Service
public class ConversationMessageServiceImpl extends ServiceImpl<ConversationMessageMapper, ConversationMessage>
        implements ConversationMessageService {

    /** 仅供展示数据解析，无状态，线程安全（readTree 是只读）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private ConversationService conversationService;

    @Resource
    private AgentStateService agentStateService;

    @Override
    public IPage<MessageVO> listPage(Long conversationId, Long userId, int current, int pageSize) {
        conversationService.getOwnedOrThrow(conversationId, userId);
        Page<ConversationMessage> page = this.page(
                new Page<>(Math.max(current, 1), Math.min(Math.max(pageSize, 1), 100)),
                new QueryWrapper<ConversationMessage>()
                        .eq("conversationId", conversationId)
                        .orderByDesc("seq")); // 取最近一页（seq 倒序）
        IPage<MessageVO> voPage = page.convert(this::toVO);
        // 页内反转成正序返回（不动 total/current/pages）
        return voPage.setRecords(ascendingWithinPage(voPage.getRecords()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int truncateFrom(Long conversationId, Long userId, Integer fromSeq) {
        if (fromSeq == null || fromSeq < 1) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "fromSeq 不合法");
        }
        Conversation c = lockAndOwn(conversationId, userId);
        // 有运行中的回答时不允许截断：否则会与循环结束时落的助手消息打架（seq/内容都对不上）。
        if (agentStateService.hasActiveRun(conversationId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前有正在进行的回答，请先停止");
        }
        int deleted = baseMapper.delete(new QueryWrapper<ConversationMessage>()
                .eq("conversationId", conversationId)
                .ge("seq", fromSeq));
        if (deleted > 0) {
            // 按【实际删除行数】回退，不按 fromSeq 推算（否则计数会漂移，见 knowledge/40 坑 2）
            int count = c.getMessageCount() == null ? 0 : c.getMessageCount();
            c.setMessageCount(Math.max(0, count - deleted));
            conversationMapper.updateById(c);
        }
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationMessage appendMessage(Long conversationId, Long userId, String role,
                                             String content, Integer tokenCount) {
        return appendMessage(conversationId, userId, role, content, tokenCount, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationMessage appendMessage(Long conversationId, Long userId, String role,
                                             String content, Integer tokenCount, Long runId) {
        return appendMessage(conversationId, userId, role, content, tokenCount, runId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationMessage appendMessage(Long conversationId, Long userId, String role,
                                             String content, Integer tokenCount, Long runId,
                                             boolean interrupted) {
        Conversation c = lockAndOwn(conversationId, userId);
        int seq = (c.getMessageCount() == null ? 0 : c.getMessageCount()) + 1;

        // 截到毫秒：列是 DATETIME(3)，LocalDateTime.now() 的纳秒部分会被截掉——
        // 提前截断可让「返回给前端的值」与「库里存的值」一致（否则响应里出现 7 位纳秒）。
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        ConversationMessage m = new ConversationMessage();
        m.setConversationId(conversationId);
        m.setRunId(runId);
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        m.setPromptVisible(1);
        m.setInterrupted(interrupted ? 1 : 0);
        m.setTokenCount(tokenCount);
        m.setCreateTime(now);
        this.save(m);

        c.setMessageCount(seq);
        c.setLastMessageTime(now);
        conversationMapper.updateById(c);
        return m;
    }

    @Override
    public List<ConversationMessage> listPromptVisibleAsc(Long conversationId) {
        return this.list(new QueryWrapper<ConversationMessage>()
                .eq("conversationId", conversationId)
                .eq("promptVisible", 1)
                .orderByAsc("seq"));
    }

    /**
     * 锁会话行并校验归属。全局逻辑删保证软删的会话查不到 → 404。
     * 必须在事务内调用（{@code FOR UPDATE} 的锁持有到事务提交）。
     */
    private Conversation lockAndOwn(Long conversationId, Long userId) {
        Conversation c = conversationMapper.selectOne(new QueryWrapper<Conversation>()
                .eq("id", conversationId)
                .last("FOR UPDATE"));
        if (c == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (!Objects.equals(c.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该会话");
        }
        return c;
    }

    // ---------------- 映射 ----------------

    @Override
    public MessageVO toVO(ConversationMessage m) {
        MessageVO vo = new MessageVO();
        vo.setId(m.getId());
        vo.setSeq(m.getSeq() == null ? 0 : m.getSeq());
        vo.setRole(m.getRole());
        vo.setContent(m.getContent());
        vo.setToolCalls(parseToolCalls(m.getToolCallsJson()));
        vo.setInterrupted(m.getInterrupted() != null && m.getInterrupted() == 1);
        vo.setTokenCount(m.getTokenCount());
        vo.setCreateTime(m.getCreateTime());
        return vo;
    }

    /** 最新在前的列表 → 正序（返回新列表，不改动入参）。 */
    static List<MessageVO> ascendingWithinPage(List<MessageVO> newestFirst) {
        List<MessageVO> copy = new ArrayList<>(newestFirst);
        Collections.reverse(copy);
        return copy;
    }

    /**
     * 从 {@code toolCallsJson} 提取工具<b>函数名</b>列表（仅供展示）。
     *
     * <p>注意：数据库里存的是原始 tool_calls JSON（对象数组），而 VO 要的是函数名——
     * 这是一次**提取**，不是直接反序列化。容错三种输入：null/空白 → null；非法 JSON → null（不抛，
     * 因为这是展示数据）；兼容纯 {@code string[]} 与 OpenAI 的 {@code [{function:{name}}]} 两种形状。
     */
    static List<String> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr == null || !arr.isArray()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    names.add(n.asText());
                    continue;
                }
                JsonNode fn = n.path("function").path("name");
                if (fn.isTextual()) {
                    names.add(fn.asText());
                } else if (n.hasNonNull("name")) {
                    names.add(n.get("name").asText());
                }
            }
            return names.isEmpty() ? null : names;
        } catch (Exception e) {
            return null;
        }
    }
}
