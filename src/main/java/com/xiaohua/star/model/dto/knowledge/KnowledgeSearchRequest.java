package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 检索测试请求
 */
@Data
public class KnowledgeSearchRequest implements Serializable {

    /**
     * 查询语句
     */
    private String query;

    /**
     * 返回条数，默认 5，最大 20
     */
    private Integer topK;

    private static final long serialVersionUID = 1L;
}
