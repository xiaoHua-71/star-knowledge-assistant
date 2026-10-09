package com.xiaohua.star.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.star.model.dto.knowledge.KnowledgeCategoryCreateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeCategoryUpdateRequest;
import com.xiaohua.star.model.entity.KnowledgeCategory;
import com.xiaohua.star.model.vo.KnowledgeCategoryVO;

import java.util.List;

/**
 * 知识库分类服务（树形，最深 3 层）
 */
public interface KnowledgeCategoryService extends IService<KnowledgeCategory> {

    KnowledgeCategoryVO create(KnowledgeCategoryCreateRequest request, Long userId);

    /**
     * 一次返回完整树（分类数量少，不分页）
     */
    List<KnowledgeCategoryVO> tree(Long userId);

    KnowledgeCategoryVO update(KnowledgeCategoryUpdateRequest request, Long userId);

    /**
     * 删除分类：其下知识库变成未分类（不删库），子分类级联删除
     */
    boolean delete(Long id, Long userId);
}
