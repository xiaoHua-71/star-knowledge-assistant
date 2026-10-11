package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建分类请求
 */
@Data
public class KnowledgeCategoryCreateRequest implements Serializable {

    /**
     * 名称，1~80 字符，同一父级下不能重名
     */
    private String name;

    /**
     * 父分类id，不传或传 0 表示根分类
     */
    private Long parentId;

    private static final long serialVersionUID = 1L;
}
