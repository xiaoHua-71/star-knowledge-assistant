package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档切片（检索的最小单位）
 *
 * <p>⚠️ 这张表没有 {@code isDelete}，删除是真删：切片是纯派生数据，可从文档正文重切；
 * 保留已删除的切片行只会在 BM25 索引重建时读到幽灵数据。
 *
 * <p>冗余存 {@code content} 是为了：BM25 索引重建、排查不连 Milvus、双写对账、tokenCount 实测。
 */
@TableName(value = "knowledge_chunk")
@Data
public class KnowledgeChunk implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属知识库id（冗余，便于按库批量操作）
     */
    private Long kbId;

    private Long docId;

    /**
     * 文档内序号，从 0 开始
     */
    private Integer chunkIndex;

    /**
     * Milvus 主键，确定性 UUID（同一段文本重建时得到同一个 id，实现幂等覆盖）
     */
    private String vectorId;

    private String content;

    /**
     * token 数（本次留空，见 knowledge/22 的「待验证」）
     */
    private Integer tokenCount;

    private Integer charCount;

    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
