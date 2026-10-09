package com.xiaohua.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.star.ai.Bm25Index;
import com.xiaohua.star.ai.Bm25IndexRegistry;
import com.xiaohua.star.ai.EmbeddingEngine;
import com.xiaohua.star.ai.MilvusStoreFactory;
import com.xiaohua.star.ai.RrfFusion;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.SearchProperties;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.mapper.KnowledgeDocumentMapper;
import com.xiaohua.star.model.dto.knowledge.KnowledgeSearchRequest;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.entity.KnowledgeChunk;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;
import com.xiaohua.star.service.KnowledgeBaseService;
import com.xiaohua.star.service.KnowledgeSearchService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务实现：混合检索与融合（M3）。
 *
 * <p>链路：<b>向量召回 + BM25 召回 → RRF 融合 → [rerank] → 来源多样性 → topK</b>。
 *
 * <p>两条召回路径先各自产出「有序的 vectorId 列表」，RRF 用<b>排名</b>把它们融合成一张分数表，
 * 再一次性回 MySQL 取候选详情（正文/文档名）组装 {@code Hit}。用排名而不是分数融合，
 * 是因为向量余弦分在 [-1,1]、BM25 分无上界，直接相加得先归一化再调权重；RRF 只看「谁排前面」，无需调参。
 *
 * <p>关于 {@code score}：它是<b>最后一级输出的分数</b>——有 rerank 就是 rerank 分，
 * 否则是 RRF 融合分（很小，约 1/60 量级），再否则是向量余弦分。它的绝对值没有直觉意义，
 * 只适合相对比较，前端不要当百分比展示。
 */
@Slf4j
@Service
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private EmbeddingEngine embeddingEngine;

    @Resource
    private MilvusStoreFactory milvusStoreFactory;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    @Resource
    private KnowledgeDocumentMapper documentMapper;

    @Resource
    private Bm25IndexRegistry bm25IndexRegistry;

    @Resource
    private SearchProperties searchProperties;

    /**
     * 重排模型可选：默认没有（ai.rerank.enabled=false），getIfAvailable() 返回 null。
     */
    @Resource
    private ObjectProvider<ScoringModel> scoringModelProvider;

    @Override
    public KnowledgeSearchResultVO search(Long kbId, KnowledgeSearchRequest request, Long userId) {
        KnowledgeBase kb = knowledgeBaseService.getOwnedOrThrow(kbId, userId);
        if (request == null || StrUtil.isBlank(request.getQuery())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "查询语句不能为空");
        }
        int topK = request.getTopK() == null
                ? DEFAULT_TOP_K
                : Math.min(Math.max(request.getTopK(), 1), MAX_TOP_K);
        assertModelMatches(kb);

        long start = System.currentTimeMillis();
        KnowledgeSearchResultVO result = new KnowledgeSearchResultVO();
        result.setQuery(request.getQuery());

        // ① 两路召回（各自按相关性有序）
        List<String> vectorIds = vectorRecall(kb, request.getQuery());
        List<String> keywordIds = keywordRecall(kb, request.getQuery());

        // ② RRF 融合：多路列表累加进同一张分数表
        Map<String, Double> fused = RrfFusion.newScoreMap();
        RrfFusion.accumulate(fused, vectorIds, searchProperties.getRrfK());
        RrfFusion.accumulate(fused, keywordIds, searchProperties.getRrfK());
        if (fused.isEmpty()) {
            result.setHits(List.of());
            result.setElapsedMs(System.currentTimeMillis() - start);
            return result;
        }

        // ③ 取候选详情（按融合分降序、截断到 maxCandidates）
        List<String> candidates = RrfFusion.topN(fused, searchProperties.getMaxCandidates());
        List<KnowledgeSearchResultVO.Hit> hits = buildHits(candidates, fused);

        // ④ rerank 精排（可选；失败/关闭都退化为融合顺序）
        ScoringModel rerank = scoringModelProvider.getIfAvailable();
        if (rerank != null && hits.size() > topK) {
            hits = rerank(request.getQuery(), rerank, hits);
        }

        // ⑤ 来源多样性：同一篇文档最多占 maxPerSource 格（不足时用跳过的补齐）
        hits = selectDiverse(hits, topK, searchProperties.getMaxPerSource());

        result.setHits(hits);
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 向量召回：把查询向量化后，到该库的 Milvus 集合里找语义相近的切片。
     *
     * <p>失败只记 warn 返回空列表，交给 BM25 那一路顶上——比「任一路挂了就整体返回空」更抗故障。
     */
    private List<String> vectorRecall(KnowledgeBase kb, String query) {
        if (!milvusStoreFactory.collectionExists(kb.getCollectionName())) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingEngine.embedQuery(query);
            EmbeddingStore<TextSegment> store = milvusStoreFactory.getStore(kb.getCollectionName(), kb.getDimension());
            return store.search(EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(searchProperties.getVectorRecall())
                            .minScore(searchProperties.getMinScore())
                            .build())
                    .matches().stream()
                    .map(EmbeddingMatch::embeddingId)
                    .toList();
        } catch (Exception e) {
            log.warn("向量召回失败，本次仅用 BM25 关键词结果 kbId={}: {}", kb.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * BM25 关键词召回：捞出字面命中查询词的切片。
     *
     * <p>失败只记 warn 返回空列表（只读操作，索引构建异常不该拖垮检索）。
     */
    private List<String> keywordRecall(KnowledgeBase kb, String query) {
        if (!searchProperties.isHybridEnabled()) {
            return List.of();
        }
        try {
            return bm25IndexRegistry.search(kb.getId(), query, searchProperties.getBm25Recall())
                    .stream()
                    .map(Bm25Index.Doc::vectorId)
                    .toList();
        } catch (Exception e) {
            log.warn("BM25 召回失败，本次仅用向量结果 kbId={}: {}", kb.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 vectorId 回 MySQL 取候选详情，组装成 Hit（保持传入的顺序）。
     *
     * <p>Milvus 只负责「找回 id 和分数」，正文与文档名一律回权威源 MySQL 取——
     * 遇到 Milvus 里有、MySQL 里没有的 id（双写漂移）就跳过并记 warn。
     */
    private List<KnowledgeSearchResultVO.Hit> buildHits(List<String> vectorIds, Map<String, Double> scoreById) {
        if (vectorIds.isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeChunk> chunkByVectorId = new HashMap<>();
        for (KnowledgeChunk chunk : chunkMapper.selectList(
                new QueryWrapper<KnowledgeChunk>().in("vectorId", vectorIds))) {
            chunkByVectorId.put(chunk.getVectorId(), chunk);
        }
        Map<Long, String> docNameById = loadDocNames(chunkByVectorId.values());

        List<KnowledgeSearchResultVO.Hit> hits = new ArrayList<>(vectorIds.size());
        for (String vectorId : vectorIds) {
            KnowledgeChunk chunk = chunkByVectorId.get(vectorId);
            if (chunk == null) {
                log.warn("检索命中未在 MySQL 找到的切片，跳过 vectorId={}", vectorId);
                continue;
            }
            KnowledgeSearchResultVO.Hit hit = new KnowledgeSearchResultVO.Hit();
            hit.setChunkId(chunk.getId());
            hit.setDocId(chunk.getDocId());
            hit.setDocName(docNameById.get(chunk.getDocId()));
            hit.setChunkIndex(chunk.getChunkIndex());
            hit.setScore(scoreById.get(vectorId));
            hit.setContent(chunk.getContent());
            hits.add(hit);
        }
        return hits;
    }

    private Map<Long, String> loadDocNames(java.util.Collection<KnowledgeChunk> chunks) {
        List<Long> docIds = chunks.stream().map(KnowledgeChunk::getDocId).distinct().toList();
        if (docIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (KnowledgeDocument doc : documentMapper.selectList(
                new QueryWrapper<KnowledgeDocument>().select("id", "name").in("id", docIds))) {
            names.put(doc.getId(), doc.getName());
        }
        return names;
    }

    /**
     * 用 rerank 精排候选。三层保护：调用失败、分数数量不匹配 → 都退回融合顺序，
     * 保证「rerank 挂了」不会拖垮检索。
     */
    private List<KnowledgeSearchResultVO.Hit> rerank(String query, ScoringModel rerank,
                                                    List<KnowledgeSearchResultVO.Hit> hits) {
        try {
            List<TextSegment> segments = hits.stream()
                    .map(h -> TextSegment.from(StrUtil.blankToDefault(h.getContent(), "")))
                    .toList();
            List<Double> scores = rerank.scoreAll(segments, query).content();
            if (scores == null || scores.size() != hits.size()) {
                log.warn("Rerank 返回分数数量不匹配，退回融合顺序");
                return hits;
            }
            List<KnowledgeSearchResultVO.Hit> ordered = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                hits.get(i).setScore(scores.get(i));
                ordered.add(hits.get(i));
            }
            ordered.sort(Comparator.comparing(KnowledgeSearchResultVO.Hit::getScore).reversed());
            return ordered;
        } catch (Exception e) {
            log.warn("Rerank 失败，退回融合顺序: {}", e.getMessage());
            return hits;
        }
    }

    /**
     * 按排序取前 limit 个，并限制<b>同一文档最多占 maxPerSource 个位置</b>，来源不足时用跳过的补齐。
     *
     * <p>为什么要限制：相邻切片自带重叠，让一篇文档占满 topK 等于把格子浪费在近似重复的内容上。
     * 为什么要补齐：不同来源本来就不足时（库里没几篇），宁可重复也不要少给知识。
     */
    static List<KnowledgeSearchResultVO.Hit> selectDiverse(List<KnowledgeSearchResultVO.Hit> ordered,
                                                          int limit, int maxPerSource) {
        if (limit <= 0) {
            return List.of();
        }
        if (maxPerSource <= 0) {
            return new ArrayList<>(ordered.stream().limit(limit).toList());
        }
        Map<Long, Integer> used = new HashMap<>();
        List<KnowledgeSearchResultVO.Hit> picked = new ArrayList<>();
        List<KnowledgeSearchResultVO.Hit> skipped = new ArrayList<>();
        for (KnowledgeSearchResultVO.Hit hit : ordered) {
            if (picked.size() >= limit) {
                break;
            }
            Long source = hit.getDocId();
            if (source == null) {
                // 来源未知：不参与限制。若用 null 当 key，一批「未知」会被当成同一篇互相挤掉
                picked.add(hit);
                continue;
            }
            if (used.getOrDefault(source, 0) < maxPerSource) {
                picked.add(hit);
                used.merge(source, 1, Integer::sum);
            } else {
                skipped.add(hit);
            }
        }
        for (KnowledgeSearchResultVO.Hit hit : skipped) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(hit);
        }
        return picked;
    }

    /**
     * 校验「query 用的模型 == 入库用的模型」。不校验的后果：维度恰好相同的话不报错，
     * 只会返回完全无关的结果（两个模型的向量空间不通用，见 knowledge/21 失败模式 1）。
     */
    private void assertModelMatches(KnowledgeBase kb) {
        if (!embeddingEngine.modelId().equals(kb.getEmbeddingModel())
                || embeddingEngine.dimension() != kb.getDimension()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "知识库使用的向量模型(" + kb.getEmbeddingModel() + "/" + kb.getDimension()
                            + ")与当前模型(" + embeddingEngine.modelId() + "/" + embeddingEngine.dimension()
                            + ")不一致，请重建索引");
        }
    }
}
