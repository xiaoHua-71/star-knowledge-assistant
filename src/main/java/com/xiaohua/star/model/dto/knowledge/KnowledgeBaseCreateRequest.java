package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建知识库请求
 */
@Data
public class KnowledgeBaseCreateRequest implements Serializable {

    /**
     * 名称，1~80 字符，同一用户下可重名
     */
    private String name;

    /**
     * 描述，最长 512 字符
     */
    private String description;

    /**
     * 所属分类id，可空
     */
    private Long categoryId;

    private static final long serialVersionUID = 1L;
}
