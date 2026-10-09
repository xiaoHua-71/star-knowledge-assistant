package com.xiaohua.star.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseCreateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseQueryRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseUpdateRequest;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.vo.KbBuildStatusVO;
import com.xiaohua.star.model.vo.KnowledgeBaseVO;

/**
 * 知识库服务
 */
public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    KnowledgeBaseVO create(KnowledgeBaseCreateRequest request, Long userId);

    IPage<KnowledgeBaseVO> listPage(KnowledgeBaseQueryRequest request, Long userId);

    KnowledgeBaseVO detail(Long id, Long userId);

    KnowledgeBaseVO update(KnowledgeBaseUpdateRequest request, Long userId);

    /**
     * 删除知识库：连同其文档、切片、Milvus 集合、原始文件一起删。不可恢复。
     */
    boolean delete(Long id, Long userId);

    /**
     * 构建进度（前端轮询用）
     */
    KbBuildStatusVO buildStatus(Long id, Long userId);

    /**
     * 取出属于该用户的知识库，否则抛异常。所有写操作的前置校验。
     */
    KnowledgeBase getOwnedOrThrow(Long id, Long userId);
}
