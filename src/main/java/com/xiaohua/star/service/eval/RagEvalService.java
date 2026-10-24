package com.xiaohua.star.service.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.star.ai.SearchOptions;
import com.xiaohua.star.config.SearchProperties;
import com.xiaohua.star.mapper.KnowledgeDocumentMapper;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.eval.EvalCase;
import com.xiaohua.star.model.eval.EvalCaseResult;
import com.xiaohua.star.model.eval.EvalCaseScore;
import com.xiaohua.star.model.eval.EvalMetrics;
import com.xiaohua.star.model.eval.EvalSet;
import com.xiaohua.star.model.eval.EvalVariant;
import com.xiaohua.star.model.eval.RagEvalReport;
import com.xiaohua.star.model.eval.VariantReport;
import com.xiaohua.star.model.enums.DocumentStatusEnum;
import com.xiaohua.star.model.vo.KnowledgeSearchResultVO;
import com.xiaohua.star.model.vo.RagCorpusSummaryVO;
import com.xiaohua.star.model.vo.RagEvalStatusVO;
import com.xiaohua.star.service.KnowledgeBaseService;
import com.xiaohua.star.service.KnowledgeSearchService;
import dev.langchain4j.model.scoring.ScoringModel;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 离线检索评估：把「检索好不好」从主观印象变成可复现的数字。
 *
 * <p>流程：<b>评估集</b>（查询 → 期望命中的文章）× <b>变体</b>（不同开关组合）
 * → 走真实检索链路 → 算<b>指标</b> → 写<b>报告</b>。</p>
 *
 * <p>三个刻意的设计：</p>
 * <ul>
 *   <li>走 {@link KnowledgeSearchService#searchWithOptions}，参数显式传入，
 *       不修改全局配置，变体之间互不污染。</li>
 *   <li>单线程串行：一次评估要跑几百次检索，并发没有收益，
 *       而且串行才能让「第几个变体 / 第几条用例」的进度真实可信。</li>
 *   <li>评估用独立的知识库和用户，不碰真实用户数据。</li>
 * </ul>
 */
@Slf4j
@Service
public class RagEvalService {

    @Resource
    private KnowledgeSearchService knowledgeSearchService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeDocumentMapper documentMapper;

    @Resource
    private EvalCorpusService corpusService;

    @Resource
    private RagEvalSetLoader evalSetLoader;

    @Resource
    private RagEvalProperties properties;

    @Resource
    private EvalReportWriter reportWriter;

    @Resource
    private SearchProperties searchProperties;

    @Resource
    private ObjectProvider<ScoringModel> scoringModelProvider;

    /** 评估的单线程执行器（串行执行） */
    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-eval");
        t.setDaemon(true);
        return t;
    });

    private volatile RagEvalStatusVO status = RagEvalStatusVO.idle();

    private volatile RagEvalReport latestReport;

    public RagEvalStatusVO getStatus() {
        return status;
    }

    public RagEvalReport getLatestReport() {
        RagEvalReport cached = latestReport;
        return cached != null ? cached : reportWriter.loadLatest();
    }

    /**
     * 提交一次评估。立即返回，实际评估在后台线程执行。
     *
     * @return true 表示已提交；false 表示已有评估在进行中
     */
    public synchronized boolean triggerEval(List<String> variantIds, List<String> caseIds) {
        String state = status.getState();
        if ("RUNNING".equals(state) || "PREPARING".equals(state)) {
            return false;
        }
        List<EvalVariant> variants = parseVariants(variantIds);
        publish("PREPARING", "已提交，等待执行", System.currentTimeMillis(),
                variants.size(), 0, 0, 0, null);
        evalExecutor.submit(() -> {
            try {
                doEval(variants, caseIds);
            } catch (Exception e) {
                log.error("评估失败", e);
            }
        });
        return true;
    }

    /**
     * 同步执行一次评估（集成测试用）。变体/用例的校验错误会直接抛出。
     */
    public RagEvalReport runNow(List<String> variantIds, List<String> caseIds) {
        return doEval(parseVariants(variantIds), caseIds);
    }

    @PreDestroy
    public void shutdown() {
        evalExecutor.shutdown();
    }

    private List<EvalVariant> parseVariants(List<String> variantIds) {
        List<String> ids = (variantIds == null || variantIds.isEmpty())
                ? properties.getDefaultVariants()
                : variantIds;
        return EvalVariant.parseAll(ids);
    }

    private synchronized RagEvalReport doEval(List<EvalVariant> variants, List<String> caseIds) {
        long start = System.currentTimeMillis();
        String runId = reportWriter.newRunId();
        publish("PREPARING", "准备评测语料", start, variants.size(), 0, 0, 0, null);
        try {
            RagCorpusSummaryVO corpus = corpusService.ensureCorpus(false);
            if (!corpus.complete()) {
                throw new IllegalStateException("评测语料不完整: " + corpus.failures());
            }
            Long evalUserId = corpusService.resolveEvalUserId();
            EvalSet evalSet = evalSetLoader.load();
            List<EvalCase> cases = selectCases(evalSet, caseIds);
            List<String> caseIdsOfRun = cases.stream().map(EvalCase::id).toList();

            Long kbId = corpus.kbId();
            KnowledgeBase kb = knowledgeBaseService.getById(kbId);
            if (kb == null) {
                throw new IllegalStateException("评测知识库不存在: " + kbId);
            }
            Set<String> indexSources = loadIndexSources(kbId);
            boolean rerankAvailable = scoringModelProvider.getIfAvailable() != null;
            SearchOptions base = SearchOptions.from(searchProperties, SearchOptions.DEFAULT_TOP_K);
            // 只和「用例集完全相同」的历史报告比：单用例冒烟混进来算出来的不是进步，
            // 是两组样本的差异
            RagEvalReport previous = reportWriter.loadPrevious(runId, caseIdsOfRun);

            log.info("开始评估：{} 条用例 × {} 个变体，kbId={}, collection={}, rerankAvailable={}",
                    cases.size(), variants.size(), kbId, kb.getCollectionName(), rerankAvailable);

            List<VariantReport> variantReports = new ArrayList<>();
            int doneCases = 0;
            for (int i = 0; i < variants.size(); i++) {
                EvalVariant variant = variants.get(i);
                publish("RUNNING", "变体 " + (i + 1) + "/" + variants.size() + ": " + variant.getLabel(),
                        start, variants.size(), i, cases.size(), doneCases, null);

                List<EvalCaseScore> scores = new ArrayList<>(cases.size());
                for (EvalCase evalCase : cases) {
                    scores.add(RetrievalMetrics.score(
                            runCase(evalCase, variant, base, kbId, evalUserId), indexSources));
                    doneCases++;
                    publish("RUNNING", "变体 " + (i + 1) + "/" + variants.size() + ": " + variant.getLabel(),
                            start, variants.size(), i, cases.size(), doneCases, null);
                }

                EvalMetrics metrics = RetrievalMetrics.aggregate(scores);
                Map<String, Double> delta = deltaVs(previous, variant.getId(), metrics);
                variantReports.add(new VariantReport(variant.getId(), variant.getLabel(),
                        variant.apply(base).describe(), metrics, scores, delta));
                log.info("变体 {} 完成：Hit@K={} / MRR={} / P50={}ms",
                        variant.getId(), String.format("%.1f%%", metrics.hitRate() * 100),
                        String.format("%.3f", metrics.mrr()), metrics.p50Millis());
            }

            KnowledgeBase after = knowledgeBaseService.getById(kbId);
            boolean collectionChanged = after == null
                    || !Objects.equals(kb.getCollectionName(), after.getCollectionName());

            RagEvalReport report = new RagEvalReport(runId, start, System.currentTimeMillis() - start,
                    kbId, kb.getCollectionName(), collectionChanged, evalSetLoader.getEvalSetFile(),
                    corpus.indexedDocs(), corpus.chunks(), corpus.complete(), corpus.staleDocs(),
                    rerankAvailable, cases.size(), caseIdsOfRun,
                    previous == null ? null : previous.runId(), variantReports);
            Path path = reportWriter.write(report);
            latestReport = report;
            publish("SUCCESS", "完成", start, variants.size(), variants.size(),
                    cases.size(), cases.size(), path.toString());
            return report;
        } catch (Exception e) {
            publish("FAILED", e.getMessage(), start, variants.size(), 0, 0, 0, null);
            throw e;
        }
    }

    private EvalCaseResult runCase(EvalCase evalCase, EvalVariant variant, SearchOptions base,
                                   Long kbId, Long userId) {
        long start = System.currentTimeMillis();
        try {
            KnowledgeSearchResultVO result = knowledgeSearchService.searchWithOptions(
                    kbId, evalCase.query(), variant.apply(base), userId);
            List<String> sources = result.getHits() == null ? List.of()
                    : result.getHits().stream()
                    .map(KnowledgeSearchResultVO.Hit::getDocName)
                    .toList();
            long cost = result.getElapsedMs() != null
                    ? result.getElapsedMs()
                    : System.currentTimeMillis() - start;
            return new EvalCaseResult(evalCase.id(), evalCase.query(), evalCase.expectedSources(),
                    sources, evalCase.tagsOrEmpty(), cost, null);
        } catch (Exception e) {
            log.warn("用例 [{}] 检索失败: {}", evalCase.id(), e.getMessage());
            return new EvalCaseResult(evalCase.id(), evalCase.query(), evalCase.expectedSources(),
                    List.of(), evalCase.tagsOrEmpty(), System.currentTimeMillis() - start, e.getMessage());
        }
    }

    private List<EvalCase> selectCases(EvalSet evalSet, List<String> caseIds) {
        List<EvalCase> enabled = evalSet.enabledCases();
        if (caseIds == null || caseIds.isEmpty()) {
            return enabled;
        }
        Set<String> wanted = new HashSet<>(caseIds);
        List<EvalCase> picked = enabled.stream().filter(item -> wanted.contains(item.id())).toList();
        if (picked.isEmpty()) {
            throw new IllegalArgumentException("caseIds 没有匹配任何启用用例: " + caseIds);
        }
        return picked;
    }

    private Set<String> loadIndexSources(Long kbId) {
        return documentMapper.selectList(new QueryWrapper<KnowledgeDocument>()
                        .select("name")
                        .eq("kbId", kbId)
                        .eq("status", DocumentStatusEnum.INDEXED.name())
                        .eq("isDelete", 0))
                .stream()
                .map(KnowledgeDocument::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Map<String, Double> deltaVs(RagEvalReport previous, String variantId, EvalMetrics now) {
        if (previous == null || previous.variants() == null) {
            return Map.of();
        }
        return previous.variants().stream()
                .filter(variant -> variantId.equals(variant.variantId()))
                .findFirst()
                .map(variant -> EvalReportWriter.delta(now, variant.metrics()))
                .orElse(Map.of());
    }

    private void publish(String state, String message, long start, int totalVariants, int doneVariants,
                         int totalCases, int doneCases, String reportPath) {
        RagEvalStatusVO vo = new RagEvalStatusVO();
        vo.setState(state);
        vo.setMessage(message);
        vo.setStartTime(start);
        vo.setTotalVariants(totalVariants);
        vo.setDoneVariants(doneVariants);
        vo.setTotalCases(totalCases);
        vo.setDoneCases(doneCases);
        vo.setReportPath(reportPath);
        this.status = vo;
    }
}
