package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库视图对象
 */
@Data
public class KnowledgeBaseVO implements Serializable {

    private Long id;

    private String name;

    private String description;

    /**
     * 所属分类，null 表示未分类
     */
    private Long categoryId;

    /**
     * EMPTY/BUILDING/READY/FAILED
     */
    private String status;

    private Integer docCount;

    private Integer chunkCount;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private LocalDateTime createTime;

    /**
     * 仅在「修改知识库」的响应里为 true：表示切片参数变了，需要重建索引。
     * 其余场景为 null。见 frontend/50。
     */
    private Boolean rebuildRequired;

    private static final long serialVersionUID = 1L;
}
