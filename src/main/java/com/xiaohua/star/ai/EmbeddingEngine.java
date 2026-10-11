package com.xiaohua.star.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量化引擎：全项目唯一把文本变成向量的入口。
 *
 * <p>它包住朴素的 {@link EmbeddingModel}，只为做三件模型本身不做的事：
 *
 * <ol>
 *   <li><b>query 侧加检索前缀、文档侧不加。</b>bge-small-zh 的 Javadoc 要求给 query 加
 *       {@value #QUERY_PREFIX}。这个不对称必须只在<b>一处</b>实现，否则会出现
 *       「某些调用路径加了、某些没加」，而漏加的后果只是召回率下降——<b>所有测试仍会通过</b>，
 *       只有离线评测能发现（见 knowledge/21 坑 3）。</li>
 *   <li><b>暴露 modelId 与 dimension。</b>检索前要校验「query 用的模型 == 入库用的模型」，
 *       而模型标识记在 knowledge_base 表里。</li>
 *   <li><b>屏蔽「向量空间不通用」的静默错误。</b>换模型后维度可能恰好相同，
 *       此时旧向量不会报错、只会返回完全无关的结果。</li>
 * </ol>
 */
@Component
public class EmbeddingEngine {

    /**
     * 模型标识，写进 knowledge_base.embeddingModel，检索前比对。
     * 改模型时这里要跟 knowledge_base 表里的值一起考虑（换模型必须重建索引）。
     */
    public static final String MODEL_ID = "bge-small-zh-v15-q";

    /**
     * bge 系列要求的 query 检索前缀（文档侧不加）。
     */
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";

    private final EmbeddingModel embeddingModel;

    public EmbeddingEngine(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String modelId() {
        return MODEL_ID;
    }

    public int dimension() {
        return embeddingModel.dimension();
    }

    /**
     * 文档侧向量化（不加前缀）。
     */
    public Embedding embedDocument(String text) {
        return embeddingModel.embed(text).content();
    }

    /**
     * query 侧向量化（<b>唯一加前缀的地方</b>）。
     */
    public Embedding embedQuery(String query) {
        return embeddingModel.embed(QUERY_PREFIX + query).content();
    }

    /**
     * 批量文档侧向量化，顺序与入参一一对应。
     */
    public List<Embedding> embedDocuments(List<TextSegment> segments) {
        return embeddingModel.embedAll(segments).content();
    }
}
