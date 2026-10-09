package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库
 *
 * <p>列名与属性名逐字符相同（camelCase），依赖 MyBatis-Plus 默认精确匹配，不加 @TableField。
 */
@TableName(value = "knowledge_base")
@Data
public class KnowledgeBase implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /**
     * 所属分类id，NULL 表示未分类
     */
    private Long categoryId;

    /**
     * 向量模型标识。检索前校验「query 用的模型 == 入库用的模型」，防换模型后静默返回无关结果
     */
    private String embeddingModel;

    /**
     * 向量维度，必须与 embeddingModel 一致
     */
    private Integer dimension;

    /**
     * Milvus 集合名，规则 star_kb_{id}
     */
    private String collectionName;

    /**
     * 切片长度（字符）
     */
    private Integer chunkSize;

    /**
     * 切片重叠（字符）
     */
    private Integer chunkOverlap;

    /**
     * EMPTY/BUILDING/READY/FAILED
     */
    private String status;

    /**
     * 文档数（冗余计数，避免列表页子查询）
     */
    private Integer docCount;

    /**
     * 切片数（冗余计数）
     */
    private Integer chunkCount;

    private Long userId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 逻辑删除（0-未删除，1-已删除）
     */
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
