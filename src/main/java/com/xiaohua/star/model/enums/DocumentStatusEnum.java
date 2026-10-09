package com.xiaohua.star.model.enums;

import java.util.List;

/**
 * 文档处理状态（knowledge_document.status）
 *
 * <p>生命周期：PENDING → PARSING → PARSED → INDEXING → INDEXED；
 * 任一步失败置 FAILED 并写 errorMsg；删除时先置 DELETING。
 *
 * <p>{@link #DELETING} 是中间态而不是终态，是为了让「删 Milvus 向量」和「删 MySQL 行」
 * 两步能分别重试：卡在 DELETING 说明减操作没做完，可重试，且此时向量可能还在，
 * 检索仍会召回它——所以 DELETING 的文档必须被检索侧排除（见 knowledge/10 第五节）。
 */
public enum DocumentStatusEnum {

    PENDING,
    PARSING,
    PARSED,
    INDEXING,
    INDEXED,
    FAILED,
    DELETING;

    /**
     * 是否正在处理中（库的汇总状态据此判定为 BUILDING）
     */
    public boolean isProcessing() {
        return this == PENDING || this == PARSING || this == PARSED || this == INDEXING;
    }

    /**
     * 是否为「可检索」的终态
     */
    public boolean isIndexed() {
        return this == INDEXED;
    }

    public static List<String> processingValues() {
        return List.of(PENDING.name(), PARSING.name(), PARSED.name(), INDEXING.name());
    }
}
