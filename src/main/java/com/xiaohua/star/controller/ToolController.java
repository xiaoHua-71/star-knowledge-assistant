package com.xiaohua.star.controller;

import com.xiaohua.star.agent.AgentToolRegistry;
import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.ToolInfoVO;
import com.xiaohua.star.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具接口。契约见 frontend/60-tools。
 *
 * <p>M7 只提供 {@code GET /tools/list}（工具清单，供前端渲染能力清单、做英文名→中文名映射）。
 * {@code GET /tools/logs}（调用记录）依赖落库，属 M8。
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

    @GetMapping("/list")
    @Operation(summary = "工具清单（能力清单 + 英文名→中文名映射）")
    public BaseResponse<List<ToolInfoVO>> list(HttpServletRequest request) {
        requireUserId(request);
        return ResultUtils.success(toolRegistry.toolInfos());
    }

    private void requireUserId(HttpServletRequest request) {
        User user = userService.getLoginUserOrNull(request);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
    }
}
