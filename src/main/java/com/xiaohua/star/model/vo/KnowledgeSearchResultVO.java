package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 检索测试结果
 *
 * <p>{@code score} 是重排后的相关性得分（rerank 关闭时就是向量余弦相似度）。
 * 绝对值没有直觉意义，<b>不要直接当百分比展示</b>，只适合相对比较。
 */
@Data
public class KnowledgeSearchResultVO implements Serializable {

    private String query;

    private Long elapsedMs;

    private List<Hit> hits;

    @Data
    public static class Hit implements Serializable {

        private Long chunkId;

        private Long docId;

        private String docName;

        private Integer chunkIndex;

        /**
         * 相关性得分，越大越相关
         */
        private Double score;

        private String content;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
