package com.xiaohua.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.mapper.KnowledgeDocumentMapper;
import com.xiaohua.star.model.dto.knowledge.KnowledgeDocumentQueryRequest;
import com.xiaohua.star.model.entity.KnowledgeChunk;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.enums.DocumentStatusEnum;
import com.xiaohua.star.model.enums.FileTypeEnum;
import com.xiaohua.star.model.vo.KnowledgeDocumentVO;
import com.xiaohua.star.service.FileStorageService;
import com.xiaohua.star.service.KbIndexService;
import com.xiaohua.star.service.KnowledgeBaseService;
import com.xiaohua.star.service.KnowledgeDocumentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档服务实现
 */
@Slf4j
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument>
        implements KnowledgeDocumentService {

    /**
     * 单文件大小上限（与 spring.servlet.multipart.max-file-size 一致，这里再挡一次给出可读提示）
     */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KbIndexService kbIndexService;

    @Resource
    private FileStorageService fileStorageService;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    @Override
    public KnowledgeDocumentVO upload(Long kbId, MultipartFile file, Long userId) {
        //做一个校验，检查当前kbId是否属于当前用户，避免越权操作
        knowledgeBaseService.getOwnedOrThrow(kbId, userId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件超过 10MB 限制");
        }
        FileTypeEnum fileType = FileTypeEnum.fromFileName(file.getOriginalFilename());
        if (fileType == null || fileType == FileTypeEnum.URL) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的文件类型，仅支持 PDF/DOCX/TXT/MD/HTML");
        }

        String filePath = fileStorageService.store(file, kbId);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setName(StrUtil.blankToDefault(file.getOriginalFilename(), "未命名文档"));
        doc.setFileType(fileType.name());
        doc.setFilePath(filePath);
        doc.setFileSize(file.getSize());
        doc.setChunkCount(0);
        doc.setStatus(DocumentStatusEnum.PENDING.name());
        doc.setUserId(userId);
        if (!save(doc)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存文档失败");
        }
        kbIndexService.submitIndex(doc.getId());
        // 回读一次拿到数据库填充的 createTime
        return toVO(this.getById(doc.getId()));
    }

    @Override
    public KnowledgeDocumentVO addUrl(Long kbId, String url, Long userId) {
        knowledgeBaseService.getOwnedOrThrow(kbId, userId);
        if (StrUtil.isBlank(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "URL 必须以 http:// 或 https:// 开头");
        }
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setName(url);
        doc.setFileType(FileTypeEnum.URL.name());
        doc.setSourceUrl(url);
        doc.setChunkCount(0);
        doc.setStatus(DocumentStatusEnum.PENDING.name());
        doc.setUserId(userId);
        if (!save(doc)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存文档失败");
        }
        kbIndexService.submitIndex(doc.getId());
        return toVO(this.getById(doc.getId()));
    }

    @Override
    public IPage<KnowledgeDocumentVO> listPage(Long kbId, KnowledgeDocumentQueryRequest request, Long userId) {
        knowledgeBaseService.getOwnedOrThrow(kbId, userId);
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<KnowledgeDocument>().eq("kbId", kbId);
        if (StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq("status", request.getStatus());
        }
        if (StrUtil.isNotBlank(request.getKeyword())) {
            wrapper.like("name", request.getKeyword());
        }
        wrapper.orderByDesc("createTime").orderByDesc("id");
        Page<KnowledgeDocument> page = this.page(new Page<>(request.getCurrent(), request.getPageSize()), wrapper);
        return page.convert(this::toVO);
    }

    @Override
    public boolean rename(Long docId, String name, Long userId) {
        KnowledgeDocument doc = getOwnedOrThrow(docId, userId);
        if (StrUtil.isBlank(name)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "名称不能为空");
        }
        if (name.length() > 255) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "名称不能超过 255 字符");
        }
        doc.setName(name);
        return updateById(doc);
    }

    @Override
    public boolean delete(Long docId, Long userId) {
        KnowledgeDocument doc = getOwnedOrThrow(docId, userId);

        // ① 先置 DELETING（可重试的中间态）
        doc.setStatus(DocumentStatusEnum.DELETING.name());
        updateById(doc);

        // ② 先删 Milvus 向量。这一步失败就直接抛——绝不能往下删 MySQL 行：
        //    否则 Milvus 里留着向量、MySQL 里没了切片，检索会召回「查不到出处的文本」
        //    （轻则答非所问，重则把已删内容答出来，见 knowledge/10 第五节）
        List<String> vectorIds = chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                        .select("vectorId").eq("docId", docId))
                .stream().map(KnowledgeChunk::getVectorId).toList();
        kbIndexService.deleteVectors(doc.getKbId(), vectorIds);

        // ③ 再删切片行（真删）与文档行（逻辑删）
        chunkMapper.delete(new QueryWrapper<KnowledgeChunk>().eq("docId", docId));
        boolean removed = removeById(docId);

        // ④ 清理原始文件并刷新库计数
        fileStorageService.delete(doc.getFilePath());
        kbIndexService.refreshKbStatus(doc.getKbId());
        return removed;
    }

    @Override
    public boolean reindex(Long docId, Long userId) {
        KnowledgeDocument doc = getOwnedOrThrow(docId, userId);
        if (DocumentStatusEnum.DELETING.name().equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文档正在删除，无法重建");
        }
        doc.setStatus(DocumentStatusEnum.PENDING.name());
        doc.setErrorMsg(null);
        updateById(doc);
        kbIndexService.submitIndex(docId);
        return true;
    }

    @Override
    public KnowledgeDocument getOwnedOrThrow(Long docId, Long userId) {
        if (docId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不能为空");
        }
        KnowledgeDocument doc = getById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        }
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该文档");
        }
        return doc;
    }

    @Override
    public KnowledgeDocumentVO toVO(KnowledgeDocument document) {
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        BeanUtils.copyProperties(document, vo);
        return vo;
    }
}
