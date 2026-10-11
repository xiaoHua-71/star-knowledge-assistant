package com.xiaohua.star.model.dto.knowledge;

import com.xiaohua.star.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库列表查询
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class KnowledgeBaseQueryRequest extends PageRequest {

    /**
     * 只看某个分类下的
     */
    private Long categoryId;

    /**
     * 按名称模糊搜索
     */
    private String keyword;
}
