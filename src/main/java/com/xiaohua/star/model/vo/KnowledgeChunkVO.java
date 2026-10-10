package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 切片视图对象（「查看文档被切成了什么」）
 */
@Data
public class KnowledgeChunkVO implements Serializable {

    private Long id;

    /**
     * 文档内序号，从 0 开始
     */
    private Integer chunkIndex;

    private Integer charCount;

    /**
     * token 数，目前恒为 null（见 knowledge/22 坑 4，待接入真实 tokenizer 回填）
     */
    private Integer tokenCount;

    /**
     * 切片正文
     */
    private String content;

    private static final long serialVersionUID = 1L;
}
