package com.xiaohua.star.model.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评估集解析单测：纯函数，<b>不启 Spring、不读文件</b>。
 *
 * <p>评估集是人手写的 JSON，用错一个字段名的代价是「评估照常跑完，只是少测了一批用例」——
 * 很难发现。所以这里把校验和报错信息也测了。</p>
 */
class EvalSetTest {

    private static String singleCase(String id, String enabled, String query, String sources) {
        return """
                {"version": 1, "cases": [
                  {"id": "%s", "enabled": %s, "query": "%s", "expectedSources": [%s]}
                ]}
                """.formatted(id, enabled, query, sources);
    }

    @Test
    void 解析合法评估集() {
        EvalSet set = EvalSet.parse(singleCase("c1", "true", "MySQL 索引为什么会失效？",
                "\"database/mysql/mysql-index-invalidation.html\""));

        assertThat(set.version()).isEqualTo(1);
        assertThat(set.cases()).hasSize(1);
        assertThat(set.cases().get(0).query()).isEqualTo("MySQL 索引为什么会失效？");
        assertThat(set.cases().get(0).expectedSources())
                .containsExactly("database/mysql/mysql-index-invalidation.html");
    }

    @Test
    void 未写enabled字段时默认启用() {
        EvalSet set = EvalSet.parse("""
                {"version": 1, "cases": [
                  {"id": "c1", "query": "问题", "expectedSources": ["a/b.html"]}
                ]}
                """);

        assertThat(set.enabledCases()).hasSize(1);
        assertThat(set.cases().get(0).isEnabled()).isTrue();
    }

    @Test
    void enabled为false的用例不出现在启用列表里但仍被保留() {
        EvalSet set = EvalSet.parse("""
                {"version": 1, "cases": [
                  {"id": "c1", "enabled": true, "query": "问题一", "expectedSources": ["a/b.html"]},
                  {"id": "c2", "enabled": false, "query": "问题二", "expectedSources": ["a/c.html"]}
                ]}
                """);

        assertThat(set.cases()).hasSize(2);
        assertThat(set.enabledCases()).extracting(EvalCase::id).containsExactly("c1");
    }

    @Test
    void 未知字段被忽略_方便在评估集里写注释() {
        EvalSet set = EvalSet.parse("""
                {"version": 1, "_说明": "临时备注", "cases": [
                  {"id": "c1", "query": "问题", "expectedSources": ["a/b.html"], "_note": "随手记"}
                ]}
                """);

        assertThat(set.enabledCases()).hasSize(1);
    }

    @Test
    void 缺少query时报错并指出是哪条用例() {
        assertThatThrownBy(() -> EvalSet.parse(singleCase("mysql-btree-01", "true", "", "\"a/b.html\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mysql-btree-01")
                .hasMessageContaining("query");
    }

    @Test
    void expectedSources为空时报错并指出是哪条用例() {
        assertThatThrownBy(() -> EvalSet.parse(singleCase("jvm-gc-01", "true", "问题", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jvm-gc-01")
                .hasMessageContaining("expectedSources");
    }

    @Test
    void expectedSources里有空值时报错() {
        assertThatThrownBy(() -> EvalSet.parse(singleCase("c1", "true", "问题", "\"a/b.html\", \"\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空值");
    }

    @Test
    void 没有任何用例时报错() {
        assertThatThrownBy(() -> EvalSet.parse("{\"version\": 1, \"cases\": []}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何用例");
    }

    @Test
    void JSON格式错误时给出可读信息() {
        assertThatThrownBy(() -> EvalSet.parse("{ 这不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 解析失败");
    }

    @Test
    void 内容为空时报错() {
        assertThatThrownBy(() -> EvalSet.parse("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内容为空");
    }

    @Test
    void 生产用的评估集文件能被正常解析() throws Exception {
        // 守住真正会跑的那份文件：它写坏了不该等到评估时才炸。
        // 工作目录不是项目根目录时跳过（而不是失败），避免环境相关的假失败
        java.nio.file.Path path = java.nio.file.Paths.get("docs/rag-eval/eval-set.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(path),
                "评估集文件不在当前工作目录，跳过");

        EvalSet set = EvalSet.parse(java.nio.file.Files.readString(path));

        assertThat(set.enabledCases()).hasSizeGreaterThanOrEqualTo(25);
        assertThat(set.cases()).extracting(EvalCase::id).doesNotHaveDuplicates();
    }
}
