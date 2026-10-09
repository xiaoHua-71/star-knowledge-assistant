package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库分类（树形，最深 3 层）
 *
 * <p>故意没有 uk(userId, parentId, name)：逻辑删除的行仍占唯一索引，会挡住「删掉再建同名分类」。
 * 同名校验在应用层用查询做（见 knowledge/10 第六节）。
 */
@TableName(value = "knowledge_category")
@Data
public class KnowledgeCategory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /**
     * 父分类id，0 表示根分类
     */
    private Long parentId;

    /**
     * 层级，从 1 开始
     */
    private Integer level;

    /**
     * 同级排序，值小的在前
     */
    private Integer sortOrder;

    private Long userId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
