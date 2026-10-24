package com.xiaohua.star.model.eval;

import com.xiaohua.star.ai.SearchOptions;
import com.xiaohua.star.config.SearchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 变体定义单测：每个变体只改自己该改的开关。
 */
class EvalVariantTest {

    private static SearchOptions base() {
        return SearchOptions.from(new SearchProperties(), 5);
    }

    @Test
    void 全开变体不改任何参数() {
        SearchOptions options = EvalVariant.ALL_ON.apply(base());

        assertThat(options).isEqualTo(base());
    }

    @Test
    void 关混合只影响hybrid开关() {
        SearchOptions options = EvalVariant.NO_HYBRID.apply(base());

        assertThat(options.hybridEnabled()).isFalse();
        assertThat(options.topK()).isEqualTo(5);
        assertThat(options.maxPerSource()).isEqualTo(2);
    }

    @Test
    void 关多样性把maxPerSource置零() {
        assertThat(EvalVariant.NO_DIVERSITY.apply(base()).maxPerSource()).isZero();
    }

    @Test
    void 关rerank只影响rerank开关() {
        SearchOptions options = EvalVariant.NO_RERANK.apply(base());

        assertThat(options.rerankAllowed()).isFalse();
        assertThat(options.hybridEnabled()).isTrue();
    }

    @Test
    void topk变体把K改成十() {
        assertThat(EvalVariant.TOPK_10.apply(base()).topK()).isEqualTo(10);
    }

    @Test
    void 按id解析且未知id给可用列表() {
        assertThat(EvalVariant.byId("no-hybrid")).isEqualTo(EvalVariant.NO_HYBRID);
        assertThatThrownBy(() -> EvalVariant.byId("no-rewrite"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all-on")
                .hasMessageContaining("no-hybrid");
    }

    @Test
    void 空列表回退到全开() {
        assertThat(EvalVariant.parseAll(List.of())).containsExactly(EvalVariant.ALL_ON);
        assertThat(EvalVariant.parseAll(null)).containsExactly(EvalVariant.ALL_ON);
    }
}
