package com.xiaohua.star.controller;

import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.conversation.ChatMessageRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.MessageVO;
import com.xiaohua.star.service.ChatService;
import com.xiaohua.star.service.ChatStreamService;
import com.xiaohua.star.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口（当前只提供非流式「简化路径」，见 frontend/30-chat-对话与流式.md）。
 *
 * <p>流式接口 {@code POST /chat/stream}（M6 最小可用版，SSE）见 {@link ChatStreamService}。
 */
@RestController
@RequestMapping("/chat")
@Slf4j
@Tag(name = "对话接口")
public class ChatController {

    @Resource
    private UserService userService;

    @Resource
    private ChatService chatService;

    @Resource
    private ChatStreamService chatStreamService;

    @PostMapping("/message")
    @Operation(summary = "发消息并一次性接收回答（非流式，简化路径：无检索、无 run）")
    public BaseResponse<MessageVO> message(@RequestBody ChatMessageRequest request,
                                           HttpServletRequest httpRequest) {
        return ResultUtils.success(chatService.chat(request, requireUserId(httpRequest)));
    }

    @PostMapping("/stream")
    @Operation(summary = "发消息并以 SSE 流式接收回答（简化路径：无检索、无 run）")
    public SseEmitter stream(@RequestBody ChatMessageRequest request, HttpServletRequest httpRequest) {
        return chatStreamService.stream(request, requireUserId(httpRequest));
    }

    /**
     * 建流失败必须回 {@code application/json} 的信封（HTTP 200），不能是 406。
     *
     * <p>根因：本方法声明返回 {@link SseEmitter}，前端又带 {@code Accept: text/event-stream}。
     * 全局 {@code GlobalExceptionHandler} 返回的 BaseResponse 没有预设 Content-Type，会走 Accept
     * 协商、无匹配 → 406。这里显式预设 {@code Content-Type: application/json}——依据 Spring 的
     * {@code AbstractMessageConverterMethodProcessor#writeWithMessageConverters}：响应 Content-Type
     * 具体时跳过 Accept 协商，故不会 406。
     *
     * <p>本方法对该控制器所有接口生效（含 {@code /chat/message}），但后者错误响应仍是同一信封、
     * HTTP 200，行为不变。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> handleBusiness(BusinessException e) {
        log.warn("对话建流失败: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    /** 兜底：建流期间的意外异常也回 JSON 信封，避免 406。口径与全局 advice 的 50000 一致。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<?>> handleUnexpected(Exception e) {
        log.error("对话建流异常", e);
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误"));
    }

    private Long requireUserId(HttpServletRequest request) {
        User user = userService.getLoginUserOrNull(request);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        return user.getId();
    }
}
