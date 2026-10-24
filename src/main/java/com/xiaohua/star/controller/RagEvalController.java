package com.xiaohua.star.controller;

import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.eval.RagEvalRunRequest;
import com.xiaohua.star.model.eval.RagEvalReport;
import com.xiaohua.star.model.vo.RagCorpusSummaryVO;
import com.xiaohua.star.model.vo.RagEvalStatusVO;
import com.xiaohua.star.service.UserService;
import com.xiaohua.star.service.eval.EvalCorpusService;
import com.xiaohua.star.service.eval.RagEvalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 离线评测接口（dev-only）。
 *
 * <p>只有 {@code rag.eval.http-enabled=true} 时才会装配。必须登录，
 * 但评估固定使用评测专用用户/知识库，不会读取调用者的数据。</p>
 *
 * <p>首次触发会同步导入 94 篇语料（本地 ONNX，本机实测约 1–2 分钟），HTTP 客户端要放宽超时；
 * 之后的运行复用已建好的索引。</p>
 */
@RestController
@RequestMapping("/rag/eval")
@ConditionalOnProperty(prefix = "rag.eval", name = "http-enabled", havingValue = "true")
@Tag(name = "离线评测接口")
@Slf4j
public class RagEvalController {

    @Resource
    private UserService userService;

    @Resource
    private RagEvalService ragEvalService;

    @Resource
    private EvalCorpusService corpusService;

    @PostMapping
    @Operation(summary = "触发一次离线评估（异步）")
    public BaseResponse<RagEvalStatusVO> trigger(@RequestBody(required = false) RagEvalRunRequest request,
                                                 HttpServletRequest httpRequest) {
        requireLogin(httpRequest);
        try {
            boolean accepted = ragEvalService.triggerEval(
                    request == null ? null : request.getVariants(),
                    request == null ? null : request.getCaseIds());
            if (!accepted) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "已有评估在运行，请稍后再试");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
        return ResultUtils.success(ragEvalService.getStatus());
    }

    @GetMapping("/status")
    @Operation(summary = "查询当前评估状态")
    public BaseResponse<RagEvalStatusVO> status(HttpServletRequest httpRequest) {
        requireLogin(httpRequest);
        return ResultUtils.success(ragEvalService.getStatus());
    }

    @GetMapping("/report/latest")
    @Operation(summary = "最近一次评估报告")
    public BaseResponse<RagEvalReport> latestReport(HttpServletRequest httpRequest) {
        requireLogin(httpRequest);
        RagEvalReport report = ragEvalService.getLatestReport();
        if (report == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "还没有评估报告");
        }
        return ResultUtils.success(report);
    }

    @PostMapping("/corpus/rebuild")
    @Operation(summary = "同步导入/重建评测语料（full=true 整库重建）")
    public BaseResponse<RagCorpusSummaryVO> rebuildCorpus(
            @RequestParam(defaultValue = "false") boolean full,
            HttpServletRequest httpRequest) {
        requireLogin(httpRequest);
        return ResultUtils.success(corpusService.ensureCorpus(full));
    }

    private void requireLogin(HttpServletRequest request) {
        if (userService.getLoginUserOrNull(request) == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
    }
}
