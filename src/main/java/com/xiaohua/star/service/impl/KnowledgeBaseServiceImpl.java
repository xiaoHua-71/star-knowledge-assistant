package com.xiaohua.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.star.ai.Bm25IndexRegistry;
import com.xiaohua.star.ai.EmbeddingEngine;
import com.xiaohua.star.ai.MilvusStoreFactory;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.MilvusProperties;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.KnowledgeBaseMapper;
import com.xiaohua.star.mapper.KnowledgeCategoryMapper;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.mapper.KnowledgeDocumentMapper;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseCreateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseQueryRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseUpdateRequest;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.entity.KnowledgeCategory;
import com.xiaohua.star.model.entity.KnowledgeChunk;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.enums.DocumentStatusEnum;
import com.xiaohua.star.model.enums.KbStatusEnum;
import com.xiaohua.star.model.vo.KbBuildStatusVO;
import com.xiaohua.star.model.vo.KnowledgeBaseVO;
import com.xiaohua.star.service.FileStorageService;
import com.xiaohua.star.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 知识库服务实现
 */
@Slf4j
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    private static final int MAX_NAME_LEN = 80;
    private static final int MAX_DESC_LEN = 512;
    private static final int DEFAULT_CHUNK_SIZE = 400;
    private static final int DEFAULT_CHUNK_OVERLAP = 80;

    @Resource
    private EmbeddingEngine embeddingEngine;

    @Resource
    private MilvusStoreFactory milvusStoreFactory;

    @Resource
    private MilvusProperties milvusProperties;

    @Resource
    private KnowledgeCategoryMapper categoryMapper;

    @Resource
    private KnowledgeDocumentMapper documentMapper;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    @Resource
    private FileStorageService fileStorageService;

    @Resource
    private Bm25IndexRegistry bm25IndexRegistry;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseVO create(KnowledgeBaseCreateRequest request, Long userId) {
        if (request == null || StrUtil.isBlank(request.getName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "知识库名称不能为空");
        }
        if (request.getName().length() > MAX_NAME_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "知识库名称不能超过 " + MAX_NAME_LEN + " 字符");
        }
        if (request.getDescription() != null && request.getDescription().length() > MAX_DESC_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "描述不能超过 " + MAX_DESC_LEN + " 字符");
        }
        requireOwnedCategory(request.getCategoryId(), userId);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setCategoryId(request.getCategoryId());
        // 模型与维度在建库时确定，检索前据此校验（换模型必须重建，见 knowledge/21）
        kb.setEmbeddingModel(embeddingEngine.modelId());
        kb.setDimension(embeddingEngine.dimension());
        // collectionName 由自增 id 派生，但插入前拿不到 id，只能先用临时唯一串占位
        kb.setCollectionName("tmp_" + UUID.randomUUID());
        kb.setChunkSize(DEFAULT_CHUNK_SIZE);
        kb.setChunkOverlap(DEFAULT_CHUNK_OVERLAP);
        kb.setStatus(KbStatusEnum.EMPTY.name());
        kb.setDocCount(0);
        kb.setChunkCount(0);
        kb.setUserId(userId);

        if (!save(kb)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建知识库失败");
        }
        // 拿到 id 后回填真实的集合名。仍然不建 Milvus 集合——等第一个文档上传时才建
        kb.setCollectionName(milvusProperties.getCollectionPrefix() + kb.getId());
        if (!updateById(kb)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "初始化集合名失败");
        }
        // 回读一次拿到数据库填充的 createTime（内存实体上没有）
        return toVO(this.getById(kb.getId()));
    }

    @Override
    public IPage<KnowledgeBaseVO> listPage(KnowledgeBaseQueryRequest request, Long userId) {
        QueryWrapper<KnowledgeBase> wrapper = new QueryWrapper<>();
        wrapper.eq("userId", userId);
        if (request.getCategoryId() != null) {
            wrapper.eq("categoryId", request.getCategoryId());
        }
        if (StrUtil.isNotBlank(request.getKeyword())) {
            wrapper.like("name", request.getKeyword());
        }
        wrapper.orderByDesc("createTime").orderByDesc("id");
        Page<KnowledgeBase> page = this.page(new Page<>(request.getCurrent(), request.getPageSize()), wrapper);
        return page.convert(this::toVO);
    }

    @Override
    public KnowledgeBaseVO detail(Long id, Long userId) {
        return toVO(getOwnedOrThrow(id, userId));
    }

    @Override
    public KnowledgeBaseVO update(KnowledgeBaseUpdateRequest request, Long userId) {
        if (request == null || request.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不能为空");
        }
        KnowledgeBase kb = getOwnedOrThrow(request.getId(), userId);

        int newChunkSize = request.getChunkSize() == null ? kb.getChunkSize() : request.getChunkSize();
        int newChunkOverlap = request.getChunkOverlap() == null ? kb.getChunkOverlap() : request.getChunkOverlap();
        validateChunkParams(newChunkSize, newChunkOverlap);

        if (StrUtil.isNotBlank(request.getName())) {
            if (request.getName().length() > MAX_NAME_LEN) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "知识库名称不能超过 " + MAX_NAME_LEN + " 字符");
            }
            kb.setName(request.getName());
        }
        if (request.getDescription() != null) {
            if (request.getDescription().length() > MAX_DESC_LEN) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "描述不能超过 " + MAX_DESC_LEN + " 字符");
            }
            kb.setDescription(request.getDescription());
        }
        if (request.getCategoryId() != null) {
            requireOwnedCategory(request.getCategoryId(), userId);
            kb.setCategoryId(request.getCategoryId());
        }

        boolean paramChanged = newChunkSize != kb.getChunkSize() || newChunkOverlap != kb.getChunkOverlap();
        kb.setChunkSize(newChunkSize);
        kb.setChunkOverlap(newChunkOverlap);

        if (!updateById(kb)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新失败");
        }

        KnowledgeBaseVO vo = toVO(this.getById(kb.getId()));
        // 切片参数变了：存量切片是旧参数切的，必须重建，否则两种粒度混在一起（见 frontend/50）
        vo.setRebuildRequired(paramChanged);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id, Long userId) {
        KnowledgeBase kb = getOwnedOrThrow(id, userId);

        // ① 先删 Milvus 集合（先减「会被召回的东西」）。集合删不掉不该挡住业务删除：
        //    孤儿集合占磁盘但无害，且不会召回——因为它的切片行马上就被删了（见 knowledge/10）
        try {
            milvusStoreFactory.dropCollection(kb.getCollectionName());
        } catch (Exception e) {
            log.warn("删除 Milvus 集合失败，继续删除业务数据: kbId={}, collection={}", id, kb.getCollectionName(), e);
        }
        // BM25 索引也一并移除（否则下次若复用同 id 会读到旧索引；正常情况不会复用，但清了更干净）
        bm25IndexRegistry.remove(id);

        // ② 删所有文档的原始文件
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new QueryWrapper<KnowledgeDocument>().eq("kbId", id));
        for (KnowledgeDocument doc : docs) {
            fileStorageService.delete(doc.getFilePath());
        }

        // ③ 真删切片行（无逻辑删除）
        chunkMapper.delete(new QueryWrapper<KnowledgeChunk>().eq("kbId", id));

        // ④ 逻辑删文档
        documentMapper.delete(new QueryWrapper<KnowledgeDocument>().eq("kbId", id));

        // ⑤ 逻辑删知识库
        return removeById(id);
    }

    @Override
    public KbBuildStatusVO buildStatus(Long id, Long userId) {
        KnowledgeBase kb = getOwnedOrThrow(id, userId);
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new QueryWrapper<KnowledgeDocument>().eq("kbId", id));

        int total = docs.size();
        int processed = 0;
        int failed = 0;
        String currentDocName = null;
        String errorMsg = null;

        for (KnowledgeDocument doc : docs) {
            DocumentStatusEnum status = parseStatus(doc.getStatus());
            if (status == DocumentStatusEnum.INDEXED) {
                processed++;
            } else if (status == DocumentStatusEnum.FAILED) {
                failed++;
                if (errorMsg == null) {
                    errorMsg = doc.getErrorMsg();
                }
            } else if (currentDocName == null && status != null && status.isProcessing()) {
                currentDocName = doc.getName();
            }
        }

        KbBuildStatusVO vo = new KbBuildStatusVO();
        vo.setTotal(total);
        vo.setProcessed(processed);
        vo.setFailedCount(failed);
        vo.setCurrentDocName(currentDocName);
        vo.setErrorMsg(errorMsg);
        vo.setStatus(resolveKbStatus(total, docs).name());
        return vo;
    }

    @Override
    public KnowledgeBase getOwnedOrThrow(Long id, Long userId) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不能为空");
        }
        KnowledgeBase kb = getById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        }
        if (!kb.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该知识库");
        }
        return kb;
    }

    private void requireOwnedCategory(Long categoryId, Long userId) {
        if (categoryId == null) {
            return;
        }
        KnowledgeCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !category.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分类不存在");
        }
    }

    private static void validateChunkParams(int chunkSize, int chunkOverlap) {
        if (chunkSize < 100 || chunkSize > 1000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chunkSize 必须在 100~1000 之间");
        }
        if (chunkOverlap < 0 || chunkOverlap > 300) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chunkOverlap 必须在 0~300 之间");
        }
        if (chunkOverlap >= chunkSize) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chunkOverlap 必须小于 chunkSize");
        }
    }

    private static KbStatusEnum resolveKbStatus(int total, List<KnowledgeDocument> docs) {
        if (total == 0) {
            return KbStatusEnum.EMPTY;
        }
        boolean anyIndexed = false;
        for (KnowledgeDocument doc : docs) {
            DocumentStatusEnum status = parseStatus(doc.getStatus());
            if (status != null && status.isProcessing()) {
                return KbStatusEnum.BUILDING;
            }
            if (status == DocumentStatusEnum.INDEXED) {
                anyIndexed = true;
            }
        }
        return anyIndexed ? KbStatusEnum.READY : KbStatusEnum.FAILED;
    }

    private static DocumentStatusEnum parseStatus(String status) {
        try {
            return status == null ? null : DocumentStatusEnum.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        BeanUtils.copyProperties(kb, vo);
        return vo;
    }
}
