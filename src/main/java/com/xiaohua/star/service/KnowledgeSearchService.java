package com.xiaohua.star.service;

import com.xiaohua.star.model.dto.knowledge.KnowledgeSearchRequest;
import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;

/**
 * 检索服务。
 *
 * <p>当前（M1）只做<b>向量召回</b>；混合检索（BM25 + RRF）与来源多样性在 M3（见 knowledge/23）。
 * rerank 走配置开关，关闭时按向量相似度排序。
 */
public interface KnowledgeSearchService {

    KnowledgeSearchResultVO search(Long kbId, KnowledgeSearchRequest request, Long userId);
}
