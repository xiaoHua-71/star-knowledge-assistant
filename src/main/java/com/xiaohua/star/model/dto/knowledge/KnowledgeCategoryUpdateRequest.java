package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 修改分类请求（只改传了值的字段）
 */
@Data
public class KnowledgeCategoryUpdateRequest implements Serializable {

    private Long id;

    private String name;

    private Long parentId;

    private Integer sortOrder;

    private static final long serialVersionUID = 1L;
}
