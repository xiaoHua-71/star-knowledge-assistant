package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 修改知识库请求（只改传了值的字段）
 */
@Data
public class KnowledgeBaseUpdateRequest implements Serializable {

    private Long id;

    private String name;

    private String description;

    private Long categoryId;

    /**
     * 切片长度，100~1000。改了必须重建索引，否则新老切片粒度混在一起
     */
    private Integer chunkSize;

    /**
     * 切片重叠，0~300，必须小于 chunkSize
     */
    private Integer chunkOverlap;

    private static final long serialVersionUID = 1L;
}
