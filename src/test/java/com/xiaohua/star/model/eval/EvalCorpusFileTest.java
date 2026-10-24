package com.xiaohua.star.model.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语料文件解析单测：首行 {@code source:} 是元数据，不能进正文。
 */
class EvalCorpusFileTest {

    @Test
    void 解析来源与正文() {
        EvalCorpusFile file = EvalCorpusFile.parse("a.txt",
                "source: https://example.com/a/b.html\n\n标题\n正文");

        assertThat(file.source()).isEqualTo("https://example.com/a/b.html");
        assertThat(file.body()).isEqualTo("\n标题\n正文");
    }

    @Test
    void 兼容CRLF() {
        EvalCorpusFile file = EvalCorpusFile.parse("a.txt",
                "source: https://example.com/a.html\r\n正文");

        assertThat(file.source()).isEqualTo("https://example.com/a.html");
        assertThat(file.body()).isEqualTo("正文");
    }

    @Test
    void 去掉UTF8_BOM() {
        EvalCorpusFile file = EvalCorpusFile.parse("a.txt",
                "﻿source: https://example.com/a.html\n正文");

        assertThat(file.source()).isEqualTo("https://example.com/a.html");
    }

    @Test
    void 缺少source首行时报错并带文件名() {
        assertThatThrownBy(() -> EvalCorpusFile.parse("bad.txt", "正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad.txt")
                .hasMessageContaining("source");
    }

    @Test
    void source为空时报错() {
        assertThatThrownBy(() -> EvalCorpusFile.parse("bad.txt", "source:   \n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source 为空");
    }
}
