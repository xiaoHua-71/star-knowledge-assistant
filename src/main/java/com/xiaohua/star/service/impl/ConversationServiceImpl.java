package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.PageRequest;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.ConversationMapper;
import com.xiaohua.star.model.dto.conversation.ConversationCreateRequest;
import com.xiaohua.star.model.dto.conversation.ConversationUpdateRequest;
import com.xiaohua.star.model.entity.Conversation;
import com.xiaohua.star.model.vo.ConversationVO;
import com.xiaohua.star.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 会话服务实现。见 knowledge/40-会话与消息管理.md。
 */
@Slf4j
@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_TITLE_LEN = 255;

    @Override
    public ConversationVO create(ConversationCreateRequest request, Long userId) {
        String title = request.getTitle();
        if (title != null) {
            validateTitle(title);
        }
        Conversation c = new Conversation();
        c.setUserId(userId);
        c.setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : title.trim());
        c.setKbIds(formatKbIds(request.getKbIds()));
        c.setPinned(0);
        c.setMessageCount(0);
        this.save(c);
        // 回读一次拿到 DB 生成的 createTime/updateTime（否则响应里 createTime 为 null）
        return toVO(this.getById(c.getId()));
    }

    @Override
    public IPage<ConversationVO> listPage(PageRequest request, Long userId) {
        // 排序固定：置顶在前 → lastMessageTime 倒序（最近说话的在前）→ id 倒序兜底。
        // 不接 sortField/sortOrder（见 frontend/20：列表页不需要自定义排序）。
        QueryWrapper<Conversation> wrapper = new QueryWrapper<Conversation>()
                .eq("userId", userId)
                .orderByDesc("pinned")
                .orderByDesc("lastMessageTime")
                .orderByDesc("id");
        Page<Conversation> page = this.page(new Page<>(Math.max(request.getCurrent(), 1),
                clampPageSize(request.getPageSize())), wrapper);
        return page.convert(this::toVO);
    }

    @Override
    public ConversationVO detail(Long id, Long userId) {
        return toVO(getOwnedOrThrow(id, userId));
    }

    @Override
    public ConversationVO update(ConversationUpdateRequest request, Long userId) {
        Conversation c = getOwnedOrThrow(request.getId(), userId);
        if (request.getTitle() != null) {
            validateTitle(request.getTitle());
            c.setTitle(request.getTitle().trim());
        }
        if (request.getPinned() != null) {
            c.setPinned(request.getPinned() ? 1 : 0);
        }
        this.updateById(c);
        return toVO(c);
    }

    @Override
    public boolean delete(Long id, Long userId) {
        getOwnedOrThrow(id, userId);
        // 逻辑删（实体有 isDelete，removeById 变成 UPDATE isDelete=1）。
        // 消息行不级联删除——保留用于审计，且删会话后消息本就不可达（见 knowledge/40）。
        return this.removeById(id);
    }

    @Override
    public Conversation getOwnedOrThrow(Long id, Long userId) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不能为空");
        }
        Conversation c = this.getById(id);
        if (c == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (!Objects.equals(c.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该会话");
        }
        return c;
    }

    private static void validateTitle(String title) {
        if (title.isBlank() || title.trim().length() > MAX_TITLE_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题长度需在 1~255 字符之间");
        }
    }

    private static int clampPageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    // ---------------- 映射 ----------------

    private ConversationVO toVO(Conversation c) {
        ConversationVO vo = new ConversationVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setPinned(c.getPinned() != null && c.getPinned() == 1);
        vo.setMessageCount(c.getMessageCount() == null ? 0 : c.getMessageCount());
        vo.setKbIds(parseKbIds(c.getKbIds()));
        vo.setLastMessageTime(c.getLastMessageTime());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }

    /**
     * 知识库 id 列表 → 逗号串。空/全空 → null（干净入库），去空白、去空项、去重（保持出现顺序）。
     */
    static String formatKbIds(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        String joined = kbIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * 逗号串 → 知识库 id 列表。null/空白 → 空列表（永不返回 null）。
     */
    static List<String> parseKbIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
