package com.xiaohua.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 连接与默认参数（application.yml 的 milvus.*）
 *
 * <p>注意这里只有「新建知识库的默认维度」和「集合名前缀」——
 * 每个知识库的真实维度与集合名记在 knowledge_base 表里，因为模型可能被换掉，
 * 检索必须用「入库时那个模型、那个维度」（见 knowledge/10）。
 */
@Component
@ConfigurationProperties(prefix = "milvus")
@Data
public class MilvusProperties {

    private String host = "localhost";

    private Integer port = 19530;

    /**
     * 集合名前缀，实际集合名 = prefix + 知识库id
     */
    private String collectionPrefix = "star_kb_";

    /**
     * 本地 embedding 模型输出维度，必须与模型一致
     */
    private Integer dimension = 512;
}
