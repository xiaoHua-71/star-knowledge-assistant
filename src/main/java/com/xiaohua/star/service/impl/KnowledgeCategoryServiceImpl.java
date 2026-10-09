package com.xiaohua.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.KnowledgeBaseMapper;
import com.xiaohua.star.mapper.KnowledgeCategoryMapper;
import com.xiaohua.star.model.dto.knowledge.KnowledgeCategoryCreateRequest;
import com.xiaohua.star.model.dto.knowledge.KnowledgeCategoryUpdateRequest;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.entity.KnowledgeCategory;
import com.xiaohua.star.model.vo.KnowledgeCategoryVO;
import com.xiaohua.star.service.KnowledgeCategoryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库分类服务实现
 *
 * <p>同名校验、层级校验都在应用层做——数据库故意没有唯一索引（逻辑删除会挡住「删掉再建同名」，
 * 见 knowledge/10 第六节）。
 */
@Service
public class KnowledgeCategoryServiceImpl extends ServiceImpl<KnowledgeCategoryMapper, KnowledgeCategory>
        implements KnowledgeCategoryService {

    private static final int MAX_NAME_LEN = 80;
    private static final int MAX_LEVEL = 3;
    private static final long ROOT_PARENT_ID = 0L;

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public KnowledgeCategoryVO create(KnowledgeCategoryCreateRequest request, Long userId) {
        String name = validateName(request.getName());
        long parentId = request.getParentId() == null ? ROOT_PARENT_ID : request.getParentId();

        int level = 1;
        if (parentId != ROOT_PARENT_ID) {
            KnowledgeCategory parent = getOwnedOrThrow(parentId, userId);
            level = parent.getLevel() + 1;
        }
        if (level > MAX_LEVEL) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分类层级不能超过 " + MAX_LEVEL + " 层");
        }
        requireNotDuplicate(userId, parentId, name, null);

        KnowledgeCategory category = new KnowledgeCategory();
        category.setName(name);
        category.setParentId(parentId);
        category.setLevel(level);
        category.setSortOrder(0);
        category.setUserId(userId);
        if (!save(category)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建分类失败");
        }
        return toVO(category);
    }

    @Override
    public List<KnowledgeCategoryVO> tree(Long userId) {
        List<KnowledgeCategory> all = list(new QueryWrapper<KnowledgeCategory>()
                .eq("userId", userId)
                .orderByAsc("sortOrder")
                .orderByAsc("id"));

        Map<Long, KnowledgeCategoryVO> voMap = new HashMap<>();
        for (KnowledgeCategory category : all) {
            voMap.put(category.getId(), toVO(category));
        }
        List<KnowledgeCategoryVO> roots = new ArrayList<>();
        for (KnowledgeCategory category : all) {
            KnowledgeCategoryVO vo = voMap.get(category.getId());
            if (category.getParentId() == null || category.getParentId() == ROOT_PARENT_ID) {
                roots.add(vo);
            } else {
                KnowledgeCategoryVO parent = voMap.get(category.getParentId());
                // 父分类被删（逻辑删）时子分类会「悬空」，这里兜底挂到根，避免整棵子树消失
                (parent == null ? roots : parent.getChildren()).add(vo);
            }
        }
        return roots;
    }

    @Override
    public KnowledgeCategoryVO update(KnowledgeCategoryUpdateRequest request, Long userId) {
        if (request == null || request.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不能为空");
        }
        KnowledgeCategory category = getOwnedOrThrow(request.getId(), userId);

        if (StrUtil.isNotBlank(request.getName())) {
            String name = validateName(request.getName());
            requireNotDuplicate(userId, category.getParentId(), name, category.getId());
            category.setName(name);
        }
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }
        if (request.getParentId() != null) {
            long newParentId = request.getParentId();
            if (newParentId == category.getId()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能把分类挂到自己下面");
            }
            int newLevel = 1;
            if (newParentId != ROOT_PARENT_ID) {
                KnowledgeCategory parent = getOwnedOrThrow(newParentId, userId);
                if (isDescendant(userId, category.getId(), newParentId)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能把分类挂到自己的子分类下");
                }
                newLevel = parent.getLevel() + 1;
            }
            if (newLevel > MAX_LEVEL) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "分类层级不能超过 " + MAX_LEVEL + " 层");
            }
            requireNotDuplicate(userId, newParentId, category.getName(), category.getId());
            category.setParentId(newParentId);
            category.setLevel(newLevel);
        }

        if (!updateById(category)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新分类失败");
        }
        return toVO(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id, Long userId) {
        getOwnedOrThrow(id, userId);

        // 收集自身 + 所有后代，一起逻辑删除
        Set<Long> toDelete = new HashSet<>();
        collectDescendants(userId, id, toDelete);

        // 这些分类下的知识库变成未分类（不删库）
        knowledgeBaseMapper.update(null, new UpdateWrapper<KnowledgeBase>()
                .set("categoryId", null)
                .in("categoryId", toDelete)
                .eq("userId", userId));

        return removeByIds(toDelete);
    }

    private void collectDescendants(Long userId, Long rootId, Set<Long> collected) {
        collected.add(rootId);
        List<KnowledgeCategory> children = list(new QueryWrapper<KnowledgeCategory>()
                .eq("userId", userId)
                .eq("parentId", rootId));
        for (KnowledgeCategory child : children) {
            if (!collected.contains(child.getId())) {
                collectDescendants(userId, child.getId(), collected);
            }
        }
    }

    private boolean isDescendant(Long userId, Long ancestorId, Long candidateId) {
        Long cursor = candidateId;
        int guard = 0;
        while (cursor != null && cursor != ROOT_PARENT_ID && guard++ < MAX_LEVEL + 2) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            KnowledgeCategory parent = getById(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    /**
     * @param excludeId 修改时要排除自己，避免「自己和自己重名」
     */
    private void requireNotDuplicate(Long userId, Long parentId, String name, Long excludeId) {
        QueryWrapper<KnowledgeCategory> wrapper = new QueryWrapper<KnowledgeCategory>()
                .eq("userId", userId)
                .eq("parentId", parentId)
                .eq("name", name);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "同级下已存在同名分类");
        }
    }

    private KnowledgeCategory getOwnedOrThrow(Long id, Long userId) {
        KnowledgeCategory category = getById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "分类不存在");
        }
        if (!category.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该分类");
        }
        return category;
    }

    private static String validateName(String name) {
        if (StrUtil.isBlank(name)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分类名称不能为空");
        }
        if (name.length() > MAX_NAME_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分类名称不能超过 " + MAX_NAME_LEN + " 字符");
        }
        return name;
    }

    private static KnowledgeCategoryVO toVO(KnowledgeCategory category) {
        KnowledgeCategoryVO vo = new KnowledgeCategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setParentId(category.getParentId());
        vo.setLevel(category.getLevel());
        vo.setSortOrder(category.getSortOrder());
        return vo;
    }
}
