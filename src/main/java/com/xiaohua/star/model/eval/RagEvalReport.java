package com.xiaohua.star.model.eval;

import java.util.List;

/**
 * 一次离线评估的完整报告。
 *
 * <p>落盘成 {@code report.json}（机器可读，下次跑时读它算差值）+ {@code report.md}（人读）。</p>
 *
 * @param runId            本次运行 ID（时间戳目录名），也是下次比对的锚点
 * @param startTime        开始时间（epoch 毫秒）
 * @param costMillis       总耗时
 * @param kbId             评测用的知识库 id
 * @param collectionName   开始时的 Milvus 集合名
 * @param collectionChanged 评估期间索引集合是否变化过（变化则前后不可比）
 * @param evalSetFile      用的哪份评估集
 * @param corpusDocs       评估库里的文档数
 * @param corpusChunks     评估库里的切片数
 * @param corpusComplete   语料是否完整（文件数 == 已索引文档数，且无反例）
 * @param staleDocs        评估库中不在当前语料目录里的旧文档数（需要 full 重建）
 * @param rerankAvailable  本次是否有可用的 rerank 模型
 * @param caseCount        参与评估的用例数
 * @param caseIds          参与评估的用例 id（用于判断两份报告是否可比）
 * @param previousRunId    上一次可比的运行 ID；首次运行或用例集不同时为 null
 * @param variants         各变体的结果
 */
public record RagEvalReport(String runId,
                            long startTime,
                            long costMillis,
                            Long kbId,
                            String collectionName,
                            boolean collectionChanged,
                            String evalSetFile,
                            int corpusDocs,
                            int corpusChunks,
                            boolean corpusComplete,
                            int staleDocs,
                            boolean rerankAvailable,
                            int caseCount,
                            List<String> caseIds,
                            String previousRunId,
                            List<VariantReport> variants) {
}
