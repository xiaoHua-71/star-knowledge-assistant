package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库分类视图对象（树形）
 */
@Data
public class KnowledgeCategoryVO implements Serializable {

    private Long id;

    private String name;

    /**
     * 父分类id，0 表示根分类
     */
    private Long parentId;

    private Integer level;

    private Integer sortOrder;

    /**
     * 子分类。树形接口里才有，叶子节点为空数组
     */
    private List<KnowledgeCategoryVO> children = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
