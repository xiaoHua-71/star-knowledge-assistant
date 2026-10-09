package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档视图对象
 */
@Data
public class KnowledgeDocumentVO implements Serializable {

    private Long id;

    private Long kbId;

    private String name;

    /**
     * PDF/DOCX/TXT/MD/HTML/URL
     */
    private String fileType;

    private Long fileSize;

    /**
     * fileType=URL 时的来源地址
     */
    private String sourceUrl;

    private Integer charCount;

    private Integer chunkCount;

    /**
     * PENDING/PARSING/PARSED/INDEXING/INDEXED/FAILED/DELETING
     */
    private String status;

    private String errorMsg;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
