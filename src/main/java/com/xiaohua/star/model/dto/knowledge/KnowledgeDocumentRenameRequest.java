package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 文档重命名请求
 */
@Data
public class KnowledgeDocumentRenameRequest implements Serializable {

    private Long id;

    private String name;

    private static final long serialVersionUID = 1L;
}
