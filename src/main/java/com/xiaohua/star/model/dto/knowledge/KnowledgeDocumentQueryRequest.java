package com.xiaohua.star.model.dto.knowledge;

import com.xiaohua.star.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档列表查询
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class KnowledgeDocumentQueryRequest extends PageRequest {

    /**
     * 按状态筛选，可空
     */
    private String status;

    /**
     * 按名称模糊搜索，可空
     */
    private String keyword;
}
