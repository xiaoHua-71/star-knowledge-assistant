package com.xiaohua.star.service;

import com.xiaohua.star.ai.SearchOptions;
import com.xiaohua.star.model.dto.knowledge.KnowledgeSearchRequest;
import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;

/**
 * 检索服务：向量 + BM25 → RRF → [rerank] → 来源多样性 → topK（见 knowledge/23）。
 *
 * <p>普通业务走 {@link #search}，参数来自 {@code knowledge.search.*}；
 * {@link #searchWithOptions} 给离线评测用，允许按变体覆盖参数而不改全局配置。</p>
 */
public interface KnowledgeSearchService {

    KnowledgeSearchResultVO search(Long kbId, KnowledgeSearchRequest request, Long userId);

    /**
     * 按显式参数检索（评测/内部使用）。业务接口不应直接调用。
     *
     * <p>默认实现只能表达 topK；真实实现会覆盖它，把混合检索/多样性/rerank 等开关都用上。
     * 保留默认方法是为了让只关心 {@link #search} 的测试替身仍能写成 lambda。</p>
     */
    default KnowledgeSearchResultVO searchWithOptions(Long kbId, String query, SearchOptions options, Long userId) {
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery(query);
        request.setTopK(options == null ? null : options.topK());
        return search(kbId, request, userId);
    }
}
