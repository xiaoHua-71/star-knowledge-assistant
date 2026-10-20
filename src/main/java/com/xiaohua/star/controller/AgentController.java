package com.xiaohua.star.controller;

import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.agent.AgentApproveRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.AgentRunVO;
import com.xiaohua.star.service.AgentRunService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 运行接口（中断 / 继续 / 查询 / 重连）。见 frontend/40-agent-中断与恢复.md。
 *
 * <p>⚠️ 与 {@link ChatController} 同样，{@code /run/{runId}/stream} 返回 {@link SseEmitter}，
 * 建流失败必须回 JSON 信封（HTTP 200）而不是 406——所以这里也放一份控制器局部 {@code @ExceptionHandler}。
 */
@RestController
@RequestMapping("/agent")
@Slf4j
@Tag(name = "Agent 运行接口")
public class AgentController {

    @Resource
    private UserService userService;

    @Resource
    private AgentRunService agentRunService;

    @PostMapping("/run/{runId}/interrupt")
    @Operation(summary = "请求停止运行（登记请求，下个安全点真正挂起）")
    public BaseResponse<Boolean> interrupt(@PathVariable long runId, HttpServletRequest httpRequest) {
        return ResultUtils.success(agentRunService.interrupt(runId, requireUserId(httpRequest)));
    }

    @PostMapping("/run/{runId}/resume")
    @Operation(summary = "继续已挂起的运行（会从断点接着执行）")
    public BaseResponse<AgentRunVO> resume(@PathVariable long runId, HttpServletRequest httpRequest) {
        return ResultUtils.success(agentRunService.resume(runId, requireUserId(httpRequest)));
    }

    @PostMapping("/run/{runId}/approve")
    @Operation(summary = "人工确认副作用操作（同意执行恰好一次 / 拒绝并回传原因）")
    public BaseResponse<AgentRunVO> approve(@PathVariable long runId,
                                            @RequestBody AgentApproveRequest request,
                                            HttpServletRequest httpRequest) {
        if (request == null || request.getApprove() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "approve 不能为空");
        }
        return ResultUtils.success(agentRunService.approve(
                runId, requireUserId(httpRequest), request.getApprove(), request.getReason()));
    }

    @GetMapping("/run/{runId}")
    @Operation(summary = "查询运行状态")
    public BaseResponse<AgentRunVO> getRun(@PathVariable long runId, HttpServletRequest httpRequest) {
        return ResultUtils.success(agentRunService.getRun(runId, requireUserId(httpRequest)));
    }

    @GetMapping("/run/{runId}/stream")
    @Operation(summary = "断线重连：补发漏掉的事件（纯读、幂等）")
    public SseEmitter stream(@PathVariable long runId,
                             @RequestParam(value = "lastEventId", required = false) Long lastEventId,
                             HttpServletRequest httpRequest) {
        return agentRunService.reconnect(runId, requireUserId(httpRequest), lastEventId);
    }

    /** 与 ChatController 同因：建流失败必须回 JSON 信封（HTTP 200），否则 Accept 协商会 406。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> handleBusiness(BusinessException e) {
        log.warn("Agent 建流失败: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<?>> handleUnexpected(Exception e) {
        log.error("Agent 建流异常", e);
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
