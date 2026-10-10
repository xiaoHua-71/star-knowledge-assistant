package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库相关配置（application.yml 的 knowledge.*）
 */
@Component
@ConfigurationProperties(prefix = "knowledge")
@Data
public class KnowledgeProperties {

    /**
     * 上传的原始文件落盘目录
     */
    private String storageDir = "./uploads";
}
