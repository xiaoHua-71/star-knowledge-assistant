package com.xiaohua.star.model.eval;

/**
 * 一篇评测语料：首行 {@code source: <url>} 是来源标识，其余是正文。
 *
 * <p>首行是语料元数据，不能喂给索引，否则「source: https://...」会进入切片和向量。
 * 解析写成纯函数，便于单测 BOM、CRLF、缺首行等边界。</p>
 *
 * @param fileName 语料文件名（仅用于报错定位）
 * @param source   来源 URL，也是检索结果的匹配依据
 * @param body     去掉首行后的正文
 */
public record EvalCorpusFile(String fileName, String source, String body) {

    private static final String PREFIX = "source:";

    public static EvalCorpusFile parse(String fileName, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("语料文件为空: " + fileName);
        }
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        int newline = text.indexOf('\n');
        String firstLine = (newline < 0 ? text : text.substring(0, newline)).trim();
        if (!firstLine.toLowerCase().startsWith(PREFIX)) {
            throw new IllegalArgumentException("语料文件缺少首行 source: " + fileName);
        }
        String source = firstLine.substring(PREFIX.length()).trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("语料文件 source 为空: " + fileName);
        }
        String body = newline < 0 ? "" : text.substring(newline + 1);
        return new EvalCorpusFile(fileName, source, body);
    }
}
