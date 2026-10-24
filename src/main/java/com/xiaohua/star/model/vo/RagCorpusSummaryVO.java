package com.xiaohua.star.model.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 评测语料导入摘要。
 *
 * @param kbId           评测知识库 id
 * @param collectionName Milvus 集合名
 * @param kbName         知识库名称
 * @param corpusFiles    语料目录里的文件数
 * @param indexedDocs    评测库中已索引的文档数（可能含陈旧文档）
 * @param chunks         评测库中的切片数
 * @param imported       本次新增或重建的文档数
 * @param skipped        本次跳过的已索引文档数
 * @param staleDocs      库中不在语料目录里的旧文档数
 * @param complete       所有语料文件是否都已成功索引
 * @param failures       失败的语料来源
 */
public record RagCorpusSummaryVO(Long kbId,
                                 String collectionName,
                                 String kbName,
                                 int corpusFiles,
                                 int indexedDocs,
                                 int chunks,
                                 int imported,
                                 int skipped,
                                 int staleDocs,
                                 boolean complete,
                                 List<String> failures) implements Serializable {
}
