package com.xiaohua.star.service;

import com.xiaohua.star.model.entity.KnowledgeDocument;

import java.util.List;

/**
 * 索引流水线：解析 → 切片 → 向量化 → 写 MySQL 切片 + 写 Milvus 向量。
 *
 * <p>它是知识库域里唯一真正碰 Milvus 写入的地方，也是唯一会推进
 * {@code knowledge_document.status} 的地方。上传/重建都提交给它异步执行。
 *
 * <p><b>双写顺序</b>（见 knowledge/10 第五节）：先插切片行、再写向量；删除则反过来，
 * 先删向量再删切片行。原则是「先加后减」——「加了没减」是安全的冗余，
 * 「减了没加」会导致召回一段查不到出处的文本。
 */
public interface KbIndexService {

    /**
     * 异步索引一个文档（上传后、单篇重建时调）
     */
    void submitIndex(Long docId);

    /**
     * 同步索引一个文档（测试/内部用；异常不抛出，转为文档 FAILED 状态）
     */
    void indexDocument(Long docId);

    /**
     * 异步重建整个知识库。full=true 全量（重切所有文档），false 增量（M3 才真正区分，
     * 当前两种都按全量处理，见 knowledge/24）
     */
    void submitRebuild(Long kbId, boolean full);

    /**
     * 删除一批向量（删文档时用）
     */
    void deleteVectors(Long kbId, List<String> vectorIds);

    /**
     * 重新计算并落库知识库的汇总状态与冗余计数
     */
    void refreshKbStatus(Long kbId);

    /**
     * 供删知识库时清理：列出该库所有切片的 vectorId
     */
    List<String> listVectorIds(Long kbId);
}
