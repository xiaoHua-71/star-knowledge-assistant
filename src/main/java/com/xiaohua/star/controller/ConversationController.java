package com.xiaohua.star.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.DeleteRequest;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.PageRequest;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.conversation.ConversationCreateRequest;
import com.xiaohua.star.model.dto.conversation.ConversationMessageQueryRequest;
import com.xiaohua.star.model.dto.conversation.ConversationUpdateRequest;
import com.xiaohua.star.model.dto.conversation.MessageTruncateRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.ConversationVO;
import com.xiaohua.star.model.vo.MessageVO;
import com.xiaohua.star.service.ConversationMessageService;
import com.xiaohua.star.service.ConversationService;
import com.xiaohua.star.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话与消息接口。
 *
 * <p>路径与响应结构与 {@code frontend/20-conversation-会话与消息.md} 一一对应。
 * 未登录统一由 {@link #requireUserId} 抛 40100，交给全局异常处理器转成统一信封。
 */
@RestController
@RequestMapping("/conversation")
@Slf4j
@Tag(name = "会话接口")
public class ConversationController {

    @Resource
    private UserService userService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ConversationMessageService conversationMessageService;

    @PostMapping("/create")
    @Operation(summary = "创建会话")
    public BaseResponse<ConversationVO> create(@RequestBody ConversationCreateRequest request,
                                               HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationService.create(request, requireUserId(httpRequest)));
    }

    @GetMapping("/list")
    @Operation(summary = "会话列表（置顶在前，然后按最后消息时间倒序）")
    public BaseResponse<IPage<ConversationVO>> list(PageRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationService.listPage(request, requireUserId(httpRequest)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "会话详情（不含消息）")
    public BaseResponse<ConversationVO> detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationService.detail(id, requireUserId(httpRequest)));
    }

    @PostMapping("/update")
    @Operation(summary = "重命名 / 置顶（传了才改）")
    public BaseResponse<ConversationVO> update(@RequestBody ConversationUpdateRequest request,
                                               HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationService.update(request, requireUserId(httpRequest)));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除会话（逻辑删）")
    public BaseResponse<Boolean> delete(@RequestBody DeleteRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationService.delete(request.getId(), requireUserId(httpRequest)));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "拉取消息历史（取最近一页，页内正序返回）")
    public BaseResponse<IPage<MessageVO>> messages(@PathVariable Long id,
                                                   ConversationMessageQueryRequest request,
                                                   HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationMessageService.listPage(
                id, requireUserId(httpRequest), request.getCurrent(), request.getPageSize()));
    }

    @PostMapping("/message/delete")
    @Operation(summary = "截断重发：删除 seq>=fromSeq 的全部消息")
    public BaseResponse<Integer> deleteMessages(@RequestBody MessageTruncateRequest request,
                                                HttpServletRequest httpRequest) {
        return ResultUtils.success(conversationMessageService.truncateFrom(
                request.getConversationId(), requireUserId(httpRequest), request.getFromSeq()));
    }

    /**
     * 取当前登录用户 id，未登录抛 40100。同 {@code KnowledgeController#requireUserId}。
     */
    private Long requireUserId(HttpServletRequest request) {
        User user = userService.getLoginUserOrNull(request);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        return user.getId();
    }
}
