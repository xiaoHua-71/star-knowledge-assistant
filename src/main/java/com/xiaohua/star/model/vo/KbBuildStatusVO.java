package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 知识库构建进度（重建/上传后前端轮询用）
 *
 * <p>进度<b>从数据库派生</b>，不落独立进度表：total/processed/failedCount 都是按文档状态
 * 现算的，currentDocName 是正在进行的那一篇。好处是无状态、不漂移；代价是每次轮询要查一次库
 * （轮询间隔 2 秒，可接受）。
 */
@Data
public class KbBuildStatusVO implements Serializable {

    /**
     * EMPTY/BUILDING/READY/FAILED
     */
    private String status;

    /**
     * 本轮需要处理的文档数
     */
    private Integer total;

    /**
     * 已处理完成的文档数
     */
    private Integer processed;

    /**
     * 失败的文档数
     */
    private Integer failedCount;

    /**
     * 正在处理哪个文档
     */
    private String currentDocName;

    /**
     * 失败原因（库级，取第一条失败的 errorMsg）
     */
    private String errorMsg;

    private static final long serialVersionUID = 1L;
}
