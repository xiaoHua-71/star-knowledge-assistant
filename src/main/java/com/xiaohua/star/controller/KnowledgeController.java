package com.xiaohua.star.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaohua.star.common.BaseResponse;
import com.xiaohua.star.common.DeleteRequest;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.common.PageRequest;
import com.xiaohua.star.common.ResultUtils;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseCreateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseQueryRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseUpdateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeCategoryCreateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeCategoryUpdateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeDocumentQueryRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeDocumentRenameRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeDocumentUrlRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeSearchRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.KbBuildStatusVO;
import com.xiaohua.star.model.vo.KnowledgeBaseVO;
import com.xiaohua.star.model.vo.KnowledgeCategoryVO;
import com.xiaohua.star.model.vo.KnowledgeChunkVO;
import com.xiaohua.star.model.vo.KnowledgeDocumentVO;
import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;
import com.xiaohua.star.service.KbIndexService;
import com.xiaohua.star.service.KnowledgeBaseService;
import com.xiaohua.star.service.KnowledgeCategoryService;
import com.xiaohua.star.service.KnowledgeDocumentService;
import com.xiaohua.star.service.KnowledgeSearchService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库接口（分类 / 知识库 / 文档 / 检索测试）
 *
 * <p>路径与响应结构与 {@code frontend/50-knowledge-知识库管理.md} 一一对应。
 * 未登录统一由 {@link #requireUserId} 抛 {@code NOT_LOGIN_ERROR}(40100)，
 * 交给全局异常处理器转成统一信封（HTTP 200 + code）。
 */
@RestController
@RequestMapping("/kb")
@Slf4j
@Tag(name = "知识库接口")
public class KnowledgeController {

    @Resource
    private UserService userService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeCategoryService knowledgeCategoryService;

    @Resource
    private KnowledgeDocumentService knowledgeDocumentService;

    @Resource
    private KnowledgeSearchService knowledgeSearchService;

    @Resource
    private KbIndexService kbIndexService;

    // ---------------- 知识库 ----------------

    @PostMapping("/create")
    @Operation(summary = "创建知识库")
    public BaseResponse<KnowledgeBaseVO> create(@RequestBody KnowledgeBaseCreateRequest request,
                                                HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeBaseService.create(request, requireUserId(httpRequest)));
    }

    @GetMapping("/list")
    @Operation(summary = "知识库列表")
    public BaseResponse<IPage<KnowledgeBaseVO>> list(KnowledgeBaseQueryRequest request,
                                                     HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeBaseService.listPage(request, requireUserId(httpRequest)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "知识库详情")
    public BaseResponse<KnowledgeBaseVO> detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeBaseService.detail(id, requireUserId(httpRequest)));
    }

    @PostMapping("/update")
    @Operation(summary = "修改知识库")
    public BaseResponse<KnowledgeBaseVO> update(@RequestBody KnowledgeBaseUpdateRequest request,
                                                HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeBaseService.update(request, requireUserId(httpRequest)));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除知识库（不可恢复，连同文档、切片、向量集合）")
    public BaseResponse<Boolean> delete(@RequestBody DeleteRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeBaseService.delete(request.getId(), requireUserId(httpRequest)));
    }

    @PostMapping("/{id}/rebuild")
    @Operation(summary = "重建索引（异步，立刻返回）")
    public BaseResponse<Boolean> rebuild(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean full,
                                         HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        knowledgeBaseService.getOwnedOrThrow(id, userId);
        kbIndexService.submitRebuild(id, full);
        return ResultUtils.success(true);
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "构建进度（前端轮询）")
    public BaseResponse<KbBuildStatusVO> status(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeBaseService.buildStatus(id, requireUserId(httpRequest)));
    }

    @PostMapping("/{id}/search")
    @Operation(summary = "检索测试")
    public BaseResponse<KnowledgeSearchResultVO> search(@PathVariable Long id,
                                                       @RequestBody KnowledgeSearchRequest request,
                                                       HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeSearchService.search(id, request, requireUserId(httpRequest)));
    }

    // ---------------- 分类 ----------------

    @PostMapping("/category/create")
    @Operation(summary = "创建分类")
    public BaseResponse<KnowledgeCategoryVO> createCategory(@RequestBody KnowledgeCategoryCreateRequest request,
                                                            HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeCategoryService.create(request, requireUserId(httpRequest)));
    }

    @GetMapping("/category/tree")
    @Operation(summary = "分类树")
    public BaseResponse<List<KnowledgeCategoryVO>> categoryTree(HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeCategoryService.tree(requireUserId(httpRequest)));
    }

    @PostMapping("/category/update")
    @Operation(summary = "修改分类")
    public BaseResponse<KnowledgeCategoryVO> updateCategory(@RequestBody KnowledgeCategoryUpdateRequest request,
                                                            HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeCategoryService.update(request, requireUserId(httpRequest)));
    }

    @PostMapping("/category/delete")
    @Operation(summary = "删除分类（子分类级联删，其下知识库变为未分类）")
    public BaseResponse<Boolean> deleteCategory(@RequestBody DeleteRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeCategoryService.delete(request.getId(), requireUserId(httpRequest)));
    }

    // ---------------- 文档 ----------------

    @PostMapping("/{kbId}/doc/upload")
    @Operation(summary = "上传文件（异步索引，返回 status=PENDING）")
    public BaseResponse<KnowledgeDocumentVO> upload(@PathVariable Long kbId,
                                                    @RequestParam("file") MultipartFile file,
                                                    HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeDocumentService.upload(kbId, file, requireUserId(httpRequest)));
    }

    @PostMapping("/{kbId}/doc/url")
    @Operation(summary = "添加网页")
    public BaseResponse<KnowledgeDocumentVO> addUrl(@PathVariable Long kbId,
                                                    @RequestBody KnowledgeDocumentUrlRequest request,
                                                    HttpServletRequest httpRequest) {
        return ResultUtils.success(
                knowledgeDocumentService.addUrl(kbId, request.getUrl(), requireUserId(httpRequest)));
    }

    @GetMapping("/{kbId}/doc/list")
    @Operation(summary = "文档列表")
    public BaseResponse<IPage<KnowledgeDocumentVO>> documentList(@PathVariable Long kbId,
                                                                 KnowledgeDocumentQueryRequest request,
                                                                 HttpServletRequest httpRequest) {
        return ResultUtils.success(
                knowledgeDocumentService.listPage(kbId, request, requireUserId(httpRequest)));
    }

    @PostMapping("/doc/update")
    @Operation(summary = "文档重命名")
    public BaseResponse<Boolean> renameDocument(@RequestBody KnowledgeDocumentRenameRequest request,
                                                HttpServletRequest httpRequest) {
        return ResultUtils.success(
                knowledgeDocumentService.rename(request.getId(), request.getName(), requireUserId(httpRequest)));
    }

    @PostMapping("/doc/delete")
    @Operation(summary = "删除文档（连同切片与向量）")
    public BaseResponse<Boolean> deleteDocument(@RequestBody DeleteRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(
                knowledgeDocumentService.delete(request.getId(), requireUserId(httpRequest)));
    }

    @PostMapping("/doc/{id}/reindex")
    @Operation(summary = "重新索引单个文档（异步）")
    public BaseResponse<Boolean> reindexDocument(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeDocumentService.reindex(id, requireUserId(httpRequest)));
    }

    @GetMapping("/doc/{id}/chunks")
    @Operation(summary = "查看文档切片（分页，按 chunkIndex 升序）")
    public BaseResponse<IPage<KnowledgeChunkVO>> listDocumentChunks(@PathVariable Long id,
                                                                   PageRequest request,
                                                                   HttpServletRequest httpRequest) {
        return ResultUtils.success(knowledgeDocumentService.listChunks(
                id, request.getCurrent(), request.getPageSize(), requireUserId(httpRequest)));
    }

    /**
     * 取当前登录用户 id，未登录抛 40100。
     *
     * <p>用 getLoginUserOrNull 而不是 getLoginUser：后者抛的是普通 RuntimeException（"未登录"），
     * 会被全局处理器归到 SYSTEM_ERROR(50000)，前端拿不到「未登录」语义。
     */
    private Long requireUserId(HttpServletRequest request) {
        User user = userService.getLoginUserOrNull(request);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        return user.getId();
    }
}
