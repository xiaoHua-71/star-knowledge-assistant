package com.xiaohua.star.service.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源匹配单测：纯函数，<b>不启 Spring、不联网</b>。
 *
 * <p>守的是评估的「标准答案」判定 —— 这里判错一次，整份报告的数字就都是错的。</p>
 */
class EvalSourceMatcherTest {

    @Test
    void 完整URL完全相同判定命中() {
        assertThat(EvalSourceMatcher.matches(
                "https://javaguide.cn/java/basis/proxy.html",
                "https://javaguide.cn/java/basis/proxy.html")).isTrue();
    }

    @Test
    void 期望写路径片段也能命中完整URL() {
        assertThat(EvalSourceMatcher.matches(
                "java/basis/proxy.html",
                "https://javaguide.cn/java/basis/proxy.html")).isTrue();
    }

    @Test
    void 反向也能命中_期望写全而实际是短路径() {
        assertThat(EvalSourceMatcher.matches(
                "https://javaguide.cn/java/basis/proxy.html",
                "java/basis/proxy.html")).isTrue();
    }

    @Test
    void 协议大小写_www前缀与结尾斜杠都被忽略() {
        assertThat(EvalSourceMatcher.matches(
                "java/basis/proxy.html",
                "HTTP://WWW.javaguide.cn/java/basis/proxy.html/")).isTrue();
    }

    @Test
    void 锚点与首尾空白被忽略() {
        assertThat(EvalSourceMatcher.matches(
                "  java/basis/proxy.html  ",
                "https://javaguide.cn/java/basis/proxy.html#section-2")).isTrue();
    }

    @Test
    void 换域名后同路径仍然命中() {
        assertThat(EvalSourceMatcher.matches(
                "java/basis/proxy.html",
                "https://my-own-site.com/java/basis/proxy.html")).isTrue();
    }

    @Test
    void 内置知识文件名能命中() {
        assertThat(EvalSourceMatcher.matches(
                "knowledge/java-backend.txt",
                "knowledge/java-backend.txt")).isTrue();
    }

    @Test
    void 同后缀的不同文章不会误配() {
        // 少了那个 / 边界的话 "proxy.html" 会匹配上 "dynamic-proxy.html"，命中率会虚高
        assertThat(EvalSourceMatcher.matches(
                "java/basis/proxy.html",
                "https://javaguide.cn/java/basis/dynamic-proxy.html")).isFalse();
    }

    @Test
    void 查询串会被保留_不同详情页不会互相命中() {
        assertThat(EvalSourceMatcher.matches(
                "developer.baidu.com/article/detail.html?id=4647986",
                "https://developer.baidu.com/article/detail.html?id=9999999")).isFalse();

        assertThat(EvalSourceMatcher.matches(
                "developer.baidu.com/article/detail.html?id=4647986",
                "https://developer.baidu.com/article/detail.html?id=4647986")).isTrue();
    }

    @Test
    void 任一侧为空时判定不命中() {
        assertThat(EvalSourceMatcher.matches(null, "java/basis/proxy.html")).isFalse();
        assertThat(EvalSourceMatcher.matches("java/basis/proxy.html", null)).isFalse();
        assertThat(EvalSourceMatcher.matches("  ", "java/basis/proxy.html")).isFalse();
        assertThat(EvalSourceMatcher.matches("java/basis/proxy.html", "")).isFalse();
    }

    @Test
    void 归一化去掉协议与锚点但保留查询串() {
        assertThat(EvalSourceMatcher.normalize("HTTPS://WWW.Example.com/a/b/?id=1#x"))
                .isEqualTo("example.com/a/b/?id=1");
    }
}
