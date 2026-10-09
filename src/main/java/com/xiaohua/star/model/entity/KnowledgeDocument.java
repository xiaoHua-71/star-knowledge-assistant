package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档
 *
 * <p>{@code contentHash} 是正文的 MD5（不是文件 MD5——同一 PDF 重导出二进制会变、正文不变），
 * 增量索引靠它判断「要不要重建这篇」。
 */
@TableName(value = "knowledge_document")
@Data
public class KnowledgeDocument implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String name;

    /**
     * PDF/DOCX/TXT/MD/HTML/URL
     */
    private String fileType;

    /**
     * 原始文件落盘路径
     */
    private String filePath;

    private Long fileSize;

    /**
     * fileType=URL 时的来源地址
     */
    private String sourceUrl;

    /**
     * 正文 MD5，增量索引的指纹
     */
    private String contentHash;

    /**
     * 正文字符数
     */
    private Integer charCount;

    private Integer chunkCount;

    /**
     * PENDING/PARSING/PARSED/INDEXING/INDEXED/FAILED/DELETING
     */
    private String status;

    private String errorMsg;

    private Long userId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
