package com.xiaohua.star.ai;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.model.entity.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BM25 索引的注册表：<b>每个知识库一个索引</b>。
 *
 * <p>为什么不是全局一个：本项目是「每个知识库一个 Milvus 集合」，检索只在一个库内做；
 * 而且文档在运行时被增删，索引必须能按库失效重建。
 *
 * <p>索引<b>惰性构建</b>：第一次检索某个库时，才从 MySQL 读出该库全部切片建索引，之后走缓存。
 * 库内容变了就 {@link #invalidate}（丢弃快照，下次检索重建）——不做增量更新，
 * 那是 knowledge/24 的范围。
 *
 * <p>数据源选 MySQL 切片表而不是 Milvus：Milvus 里的文本是给检索用的冗余副本，
 * 权威源是 `knowledge_chunk`（含无逻辑删除的真相），从它建索引才不会有幽灵数据。
 */
@Slf4j
@Component
public class Bm25IndexRegistry {

    private final KnowledgeChunkMapper chunkMapper;

    private final Map<Long, Bm25Index> indexes = new ConcurrentHashMap<>();

    /**
     * 每库一把构建锁。避免「同一库被并发首查」时重复构建整库索引。
     */
    private final Map<Long, Object> buildLocks = new ConcurrentHashMap<>();

    public Bm25IndexRegistry(KnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /**
     * 关键词检索。
     *
     * @return 命中的段，按 BM25 得分降序；库为空或无命中返回空列表
     */
    public List<Bm25Index.Doc> search(Long kbId, String query, int topN) {
        return getOrBuild(kbId).search(query, topN);
    }

    /**
     * 库内容变了就调用它：丢弃快照，下次检索重建。
     *
     * <p>⚠️ <b>凡是改变库内切片的操作都必须调用</b>（新增/删除文档、重建、删库），
     * 漏一个就会出现「新文档搜不到 / 删掉的还能搜到」。调用点清单见 knowledge/23。
     */
    public void invalidate(Long kbId) {
        if (kbId != null) {
            indexes.remove(kbId);
        }
    }

    /**
     * 彻底移除（删知识库时用，顺便清掉构建锁，避免 map 里留垃圾）
     */
    public void remove(Long kbId) {
        if (kbId != null) {
            indexes.remove(kbId);
            buildLocks.remove(kbId);
        }
    }

    private Bm25Index getOrBuild(Long kbId) {
        Bm25Index cached = indexes.get(kbId);
        if (cached != null) {
            return cached;
        }
        Object lock = buildLocks.computeIfAbsent(kbId, k -> new Object());
        synchronized (lock) {
            Bm25Index current = indexes.get(kbId);
            if (current != null) {
                return current;
            }
            List<Bm25Index.Doc> docs = loadDocs(kbId);
            Bm25Index built = new Bm25Index();
            built.rebuild(docs);
            indexes.put(kbId, built);
            log.info("BM25 索引构建完成 kbId={}, docs={}", kbId, built.size());
            return built;
        }
    }

    private List<Bm25Index.Doc> loadDocs(Long kbId) {
        return chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                        .select("vectorId", "docId", "chunkIndex", "content")
                        .eq("kbId", kbId))
                .stream()
                .map(chunk -> new Bm25Index.Doc(
                        chunk.getVectorId(),
                        chunk.getDocId(),
                        chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex(),
                        StrUtil.blankToDefault(chunk.getContent(), "")))
                .toList();
    }
}
