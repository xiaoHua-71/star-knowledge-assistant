package com.xiaohua.star.ai;

import com.xiaohua.star.config.SearchProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索参数对象单测：生产配置映射、topK 截断、变体覆盖。
 */
class SearchOptionsTest {

    @Test
    void 生产配置映射到参数且topK有默认值() {
        SearchOptions options = SearchOptions.from(new SearchProperties(), null);

        assertThat(options.hybridEnabled()).isTrue();
        assertThat(options.vectorRecall()).isEqualTo(20);
        assertThat(options.bm25Recall()).isEqualTo(20);
        assertThat(options.rrfK()).isEqualTo(60);
        assertThat(options.maxCandidates()).isEqualTo(30);
        assertThat(options.maxPerSource()).isEqualTo(2);
        assertThat(options.minScore()).isZero();
        assertThat(options.rerankAllowed()).isTrue();
        assertThat(options.topK()).isEqualTo(SearchOptions.DEFAULT_TOP_K);
    }

    @Test
    void topK被截断到合法区间() {
        assertThat(SearchOptions.from(new SearchProperties(), 0).topK()).isEqualTo(1);
        assertThat(SearchOptions.from(new SearchProperties(), -5).topK()).isEqualTo(1);
        assertThat(SearchOptions.from(new SearchProperties(), 99).topK()).isEqualTo(20);
        assertThat(SearchOptions.from(new SearchProperties(), 10).topK()).isEqualTo(10);
    }

    @Test
    void with系列只改目标字段() {
        SearchOptions base = SearchOptions.from(new SearchProperties(), 5);

        assertThat(base.withHybrid(false).hybridEnabled()).isFalse();
        assertThat(base.withHybrid(false).topK()).isEqualTo(5);

        assertThat(base.withRerank(false).rerankAllowed()).isFalse();
        assertThat(base.withMaxPerSource(0).maxPerSource()).isZero();
        assertThat(base.withTopK(10).topK()).isEqualTo(10);
        assertThat(base.withMinScore(0.5).minScore()).isEqualTo(0.5);
    }

    @Test
    void describe包含所有可调参数() {
        String text = SearchOptions.from(new SearchProperties(), 5).describe();

        assertThat(text).contains("hybrid=true", "topK=5", "maxPerSource=2", "rerank=true");
    }
}
