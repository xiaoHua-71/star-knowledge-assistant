package com.xiaohua.star.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaohua.star.agent.AgentToolRegistry;
import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.tool.ToolLogQueryRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.CallSummaryVO;
import com.xiaohua.star.model.vo.ToolInfoVO;
import com.xiaohua.star.model.vo.ToolLogVO;
import com.xiaohua.star.service.ObservabilityService;
import com.xiaohua.star.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具接口。契约见 frontend/60-tools。
 *
 * <p>{@code GET /tools/list}（M7）：工具清单 + 英文名→中文名映射。
 * {@code GET /tools/logs} / {@code /tools/logs/summary}（M12）：工具调用记录与 token/成本汇总。
 */
@RestController
@RequestMapping("/tools")
@Slf4j
@Tag(name = "工具接口")
public class ToolController {

    @Resource
    private UserService userService;

    @Resource
    private AgentToolRegistry toolRegistry;

    @Resource
    private ObservabilityService observabilityService;

    @GetMapping("/list")
    @Operation(summary = "工具清单（能力清单 + 英文名→中文名映射）")
    public BaseResponse<List<ToolInfoVO>> list(HttpServletRequest request) {
        requireUserId(request);
        return ResultUtils.success(toolRegistry.toolInfos());
    }

    @GetMapping("/logs")
    @Operation(summary = "工具调用记录（分页）")
    public BaseResponse<IPage<ToolLogVO>> logs(ToolLogQueryRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(observabilityService.listToolLogs(request, requireUserId(httpRequest)));
    }

    @GetMapping("/logs/summary")
    @Operation(summary = "调用汇总（token、成本、工具失败/重放次数）")
    public BaseResponse<CallSummaryVO> summary(
            @RequestParam(value = "runId", required = false) Long runId,
            HttpServletRequest httpRequest) {
        return ResultUtils.success(observabilityService.summary(requireUserId(httpRequest), runId));
    }

    private Long requireUserId(HttpServletRequest request) {
        User user = userService.getLoginUserOrNull(request);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        return user.getId();
    }
}
