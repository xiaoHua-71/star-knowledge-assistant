package com.xiaohua.star.model.enums;

/**
 * 知识库汇总状态（knowledge_base.status）
 *
 * <p>它是「库内所有文档状态的汇总」，不是单个文档的状态——后者见 {@link DocumentStatusEnum}。
 * 规则：有文档在处理 → BUILDING；全部处理完且至少一篇成功 → READY；一篇都没成功 → FAILED；无文档 → EMPTY。
 */
public enum KbStatusEnum {

    /**
     * 还没有任何文档
     */
    EMPTY,
    /**
     * 有文档正在解析/向量化
     */
    BUILDING,
    /**
     * 可正常检索
     */
    READY,
    /**
     * 建索引失败，看 knowledge_document.errorMsg
     */
    FAILED
}
