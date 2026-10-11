package com.xiaohua.star.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BM25 关键词索引：混合检索里负责「字面匹配」的那一路。
 *
 * <p>向量检索（双塔）擅长语义相近，但对「布隆过滤器」「Seata」这类专有名词不敏感——
 * 向量化会把术语平滑掉。而关键词能字面命中时，是极强的相关性信号。
 * 两者各召回一批候选，融合后交给 rerank 精排，就是工业界的标准混合检索。
 *
 * <p>BM25 是 TF-IDF 的改进版，核心是对「词频」做饱和处理（出现再多也不会无限加分）
 * 并按文档长度归一化（长文档天然命中更多词，要惩罚）。
 *
 * <p><b>本索引纯内存、不可变快照、volatile 整体替换</b>：构建全新快照再原子换掉，
 * 不做增量修改，读线程无锁安全。它不是 Spring bean——因为本项目是「每个知识库一个索引」，
 * 实例由 {@link Bm25IndexRegistry} 按库持有。
 *
 * <p><b>移植说明</b>：本类从 salary-leap 的 {@code Bm25Index} 移植（那套已在真实评测集上验证）。
 * 唯一改动是索引项从 langchain4j 的 {@code TextSegment} 换成 {@link Doc}——
 * 因为我们要用稳定的 {@code vectorId} 做融合键，而不是像 salary-leap 那样没有 id、
 * 只能退而用「文本 trim」当去重键。
 */
public class Bm25Index {

    /**
     * 词频饱和系数：越大越看重「多出现几次」，1.2 是通用默认值
     */
    private static final double K1 = 1.2;

    /**
     * 文档长度归一化系数：0 表示不惩罚长文档，1 表示完全归一化，0.75 是通用默认值
     */
    private static final double B = 0.75;

    /**
     * 当前生效的索引快照（不可变，volatile 整体替换）
     */
    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * 索引里的一段文本。{@code vectorId} 是确定性 UUID，同时是 MySQL 切片与 Milvus 的主键，
     * 因此可以直接当 RRF 的融合键与回表取详情的键。
     */
    public record Doc(String vectorId, long docId, int chunkIndex, String text) {
    }

    /**
     * 用一批切片重建索引。传入空列表则清空索引。
     */
    public void rebuild(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            snapshot = Snapshot.empty();
            return;
        }
        snapshot = build(docs);
    }

    /**
     * 关键词检索：返回得分最高的前 topN 段，只保留得分大于 0 的
     * （一个字面 token 都没命中就不返回）。
     *
     * @return 命中的段，按 BM25 得分降序；无命中返回空列表
     */
    public List<Doc> search(String query, int topN) {
        // 快照只读一次，避免遍历途中被重建线程替换成另一个快照
        Snapshot s = snapshot;
        if (query == null || query.isBlank() || topN <= 0 || s.docs().isEmpty()) {
            return List.of();
        }
        Set<String> queryTokens = new LinkedHashSet<>(tokenize(query));
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int docCount = s.docs().size();
        double avgLength = s.avgDocLength() <= 0 ? 1.0 : s.avgDocLength();
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            Map<String, Integer> termFreq = s.termFreqs().get(i);
            int docLength = s.docLengths()[i];
            double score = 0;
            for (String token : queryTokens) {
                Integer tf = termFreq.get(token);
                if (tf == null) {
                    continue;
                }
                score += idf(token, docCount, s.docFreq()) * saturate(tf, docLength, avgLength);
            }
            if (score > 0) {
                scored.add(new Scored(i, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream()
                .limit(topN)
                .map(item -> s.docs().get(item.index()))
                .toList();
    }

    /**
     * 当前索引了多少段（测试/可观测用）
     */
    public int size() {
        return snapshot.docs().size();
    }

    /**
     * IDF：Lucene 的变体（分母多加 1），保证恒为正数，避免「高频词」算出负分反而减分。
     */
    private static double idf(String token, int docCount, Map<String, Integer> docFreq) {
        int df = docFreq.getOrDefault(token, 0);
        return Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
    }

    /**
     * 单个 token 的 TF 分量：词频饱和 + 文档长度归一化。
     */
    private static double saturate(int tf, int docLength, double avgLength) {
        return (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLength / avgLength));
    }

    private Snapshot build(List<Doc> docs) {
        // 相同 vectorId 只索引一次（防御性去重；正常情况 vectorId 本就唯一）
        Map<String, Doc> unique = new LinkedHashMap<>();
        for (Doc doc : docs) {
            unique.putIfAbsent(doc.vectorId(), doc);
        }
        List<Doc> uniques = List.copyOf(unique.values());

        int docCount = uniques.size();
        int[] docLengths = new int[docCount];
        List<Map<String, Integer>> termFreqs = new ArrayList<>(docCount);
        Map<String, Integer> docFreq = new HashMap<>();
        long totalLength = 0;
        for (int i = 0; i < docCount; i++) {
            List<String> tokens = tokenize(uniques.get(i).text());
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }
            termFreqs.add(termFreq);
            docLengths[i] = tokens.size();
            totalLength += tokens.size();
            for (String token : termFreq.keySet()) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }
        return new Snapshot(uniques, docLengths, termFreqs, docFreq, (double) totalLength / docCount);
    }

    /**
     * 分词：不引入分词库，中文按「相邻两字」切成 bigram，英文数字按小写单词切，标点直接丢弃。
     *
     * <p>中文之所以能这么切：BM25 只看「token 是否重合」，「缓存穿透」切成
     * 缓存/存穿/穿透 后，查询和文档会切成同一组 token，照样能精确对上；
     * 这是 Lucene CJK 分析器的常规做法，代价是可能产生跨词噪声。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (isHan(c)) {
                int start = i;
                while (i < length && isHan(text.charAt(i))) {
                    i++;
                }
                appendHanBigrams(tokens, text, start, i);
            } else if (isAsciiWord(c)) {
                int start = i;
                while (i < length && isAsciiWord(text.charAt(i))) {
                    i++;
                }
                tokens.add(text.substring(start, i).toLowerCase(Locale.ROOT));
            } else {
                // 标点、空白、换行等一律丢弃
                i++;
            }
        }
        return tokens;
    }

    private static void appendHanBigrams(List<String> tokens, String text, int start, int end) {
        int runLength = end - start;
        if (runLength == 1) {
            tokens.add(text.substring(start, end));
            return;
        }
        for (int i = start; i < end - 1; i++) {
            tokens.add(text.substring(i, i + 2));
        }
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * 索引快照：字段全部 final，构建完即不可变，可被检索线程无锁安全读取。
     */
    private record Snapshot(List<Doc> docs,
                            int[] docLengths,
                            List<Map<String, Integer>> termFreqs,
                            Map<String, Integer> docFreq,
                            double avgDocLength) {

        static Snapshot empty() {
            return new Snapshot(List.of(), new int[0], List.of(), Map.of(), 0);
        }
    }

    /**
     * 检索中间结果：文档下标 + 得分
     */
    private record Scored(int index, double score) {
    }
}
