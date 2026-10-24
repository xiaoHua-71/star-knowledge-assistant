package com.xiaohua.star.service.eval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定回归测试（只起一个最小上下文，不连 MySQL/Redis/Milvus）。
 *
 * <p>守的是「{@code @Value} 绑不上 YAML 列表、静默退化成空列表」这个坑
 * （见 knowledge/19 §九）。这里用 {@code @ConfigurationProperties}，配测试守住它。</p>
 *
 * <p>必须显式 {@code @EnableConfigurationProperties}：{@code ApplicationContextRunner}
 * 不加载自动配置，默认没有属性绑定后处理器。</p>
 */
class RagEvalPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingConfig.class);

    @EnableConfigurationProperties(RagEvalProperties.class)
    static class BindingConfig {
    }

    @Test
    void 列表形式的默认变体能被绑定() {
        runner.withPropertyValues(
                        "rag.eval.default-variants[0]=all-on",
                        "rag.eval.default-variants[1]=no-hybrid")
                .run(context -> {
                    RagEvalProperties properties = context.getBean(RagEvalProperties.class);
                    assertThat(properties.getDefaultVariants()).containsExactly("all-on", "no-hybrid");
                });
    }

    @Test
    void 未配置时字段保持默认值() {
        runner.run(context -> {
            RagEvalProperties properties = context.getBean(RagEvalProperties.class);
            assertThat(properties.getEvalSetFile()).isEqualTo("docs/rag-eval/eval-set.json");
            assertThat(properties.getReportDir()).isEqualTo("docs/rag-eval/reports");
            assertThat(properties.getDefaultVariants())
                    .containsExactly("all-on", "no-hybrid", "no-diversity", "no-rerank", "topk-10");
            assertThat(properties.isHttpEnabled()).isFalse();
        });
    }

    @Test
    void 标量配置能被覆盖() {
        runner.withPropertyValues(
                        "rag.eval.eval-set-file=/tmp/my-eval.json",
                        "rag.eval.report-dir=/tmp/reports",
                        "rag.eval.http-enabled=true")
                .run(context -> {
                    RagEvalProperties properties = context.getBean(RagEvalProperties.class);
                    assertThat(properties.getEvalSetFile()).isEqualTo("/tmp/my-eval.json");
                    assertThat(properties.getReportDir()).isEqualTo("/tmp/reports");
                    assertThat(properties.isHttpEnabled()).isTrue();
                });
    }
}
