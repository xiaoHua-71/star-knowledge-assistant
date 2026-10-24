package com.xiaohua.star.service.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 离线评测配置（application.yml 的 rag.eval.*）。
 *
 * <p>语料、评估集、报告目录都相对项目根目录。{@code userId} 为空时自动创建/复用
 * 专用的 {@code rag-eval} 用户；指定后则使用该用户（其知识库列表会多出一个评测库）。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.eval")
@Data
public class RagEvalProperties {

    /** 是否暴露 HTTP 触发接口；生产必须关 */
    private boolean httpEnabled = false;

    private String evalSetFile = "docs/rag-eval/eval-set.json";

    private String corpusDir = "docs/rag-eval/corpus";

    private String reportDir = "docs/rag-eval/reports";

    private String corpusKbName = "rag-eval-corpus";

    /** 为空表示自动创建/复用专用评测用户 */
    private Long userId;

    private List<String> defaultVariants = List.of(
            "all-on", "no-hybrid", "no-diversity", "no-rerank", "topk-10");
}
