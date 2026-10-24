package com.xiaohua.star.model.dto.eval;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 触发一次离线评估。
 *
 * <p>两个字段都可以为空：为空时分别使用 {@code rag.eval.default-variants}
 * 和评估集里全部启用的用例。</p>
 */
@Data
public class RagEvalRunRequest implements Serializable {

    /** 要跑的变体 id，如 all-on / no-hybrid / topk-10 */
    private List<String> variants;

    /** 只跑指定用例；为空跑全部 */
    private List<String> caseIds;

    private static final long serialVersionUID = 1L;
}
