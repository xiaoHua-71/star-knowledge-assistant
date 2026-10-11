package com.xiaohua.star.model.dto.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 添加网页请求
 */
@Data
public class KnowledgeDocumentUrlRequest implements Serializable {

    /**
     * 网页地址，http/https
     */
    private String url;

    private static final long serialVersionUID = 1L;
}
