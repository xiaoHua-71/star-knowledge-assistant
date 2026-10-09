package com.xiaohua.star.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.star.ai.Bm25IndexRegistry;
import com.xiaohua.star.ai.ChunkSplitterFactory;
import com.xiaohua.star.ai.DocumentParserFactory;
import com.xiaohua.star.ai.EmbeddingEngine;
import com.xiaohua.star.ai.MilvusStoreFactory;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.KbExecutorConfig;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.KnowledgeBaseMapper;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.mapper.KnowledgeDocumentMapper;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.entity.KnowledgeChunk;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.enums.DocumentStatusEnum;
import com.xiaohua.star.model.enums.FileTypeEnum;
import com.xiaohua.star.model.enums.KbStatusEnum;
import com.xiaohua.star.service.FileStorageService;
import com.xiaohua.star.service.KbIndexService;
import com.xiaohua.star.service.KnowledgeChunkService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 索引流水线实现。
 *
 * <p>状态推进严格按 knowledge/10 第五节的新增顺序：
 * {@code PENDING → PARSING → PARSED → INDEXING → INDEXED}，
 * 写库顺序是「先写切片行、再写向量」；失败置 FAILED 并把原因写进 errorMsg。
 *
 * <p>本类<b>不</b>依赖 KnowledgeDocumentService，避免和它形成环：
 * 上传只负责落一行 PENDING 文档，随后把 docId 交给这里。
 */
@Slf4j
@Service
public class KbIndexServiceImpl implements KbIndexService {

    /**
     * errorMsg 列宽
     */
    private static final int MAX_ERROR_LEN = 1000;

    @Resource
    private KnowledgeDocumentMapper documentMapper;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    @Resource
    private KnowledgeBaseMapper baseMapper;

    @Resource
    private KnowledgeChunkService chunkService;

    @Resource
    private EmbeddingEngine embeddingEngine;

    @Resource
    private MilvusStoreFactory milvusStoreFactory;

    @Resource
    private DocumentParserFactory documentParserFactory;

    @Resource
    private ChunkSplitterFactory chunkSplitterFactory;

    @Resource
    private FileStorageService fileStorageService;

    @Resource
    private Bm25IndexRegistry bm25IndexRegistry;

    @Resource(name = KbExecutorConfig.KB_INDEX_EXECUTOR)
    private Executor kbIndexExecutor;

    @Override
    public void submitIndex(Long docId) {
        kbIndexExecutor.execute(() -> {
            try {
                indexDocument(docId);
            } catch (Exception e) {
                // indexDocument 内部已处理异常并置 FAILED，这里只兜底日志
                log.error("索引文档任务异常 docId={}", docId, e);
            }
        });
    }

    @Override
    public void indexDocument(Long docId) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            log.warn("文档不存在，跳过索引 docId={}", docId);
            return;
        }
        if (DocumentStatusEnum.DELETING.name().equals(doc.getStatus())) {
            log.info("文档正在删除，跳过索引 docId={}", docId);
            return;
        }
        KnowledgeBase kb = baseMapper.selectById(doc.getKbId());
        if (kb == null) {
            markFailed(doc, "所属知识库不存在");
            return;
        }

        try {
            // ① 解析
            setStatus(doc, DocumentStatusEnum.PARSING);
            Document parsed = parse(doc);
            String text = parsed.text();
            if (StrUtil.isBlank(text)) {
                markFailed(doc, "解析结果为空（可能是扫描件 PDF 或空文件）");
                refreshKbStatus(kb.getId());
                return;
            }
            doc.setCharCount(text.length());
            doc.setContentHash(SecureUtil.md5(text));
            setStatus(doc, DocumentStatusEnum.PARSED);
            refreshKbStatus(kb.getId());

            // ② 切片
            List<TextSegment> segments = chunkSplitterFactory.split(text, kb.getChunkSize(), kb.getChunkOverlap());
            if (segments.isEmpty()) {
                markFailed(doc, "切片结果为空");
                refreshKbStatus(kb.getId());
                return;
            }

            // ③ 清掉这篇文档的旧切片与旧向量（重试/重建场景）
            clearDocumentData(doc, kb);

            setStatus(doc, DocumentStatusEnum.INDEXING);

            // ④ 先写 MySQL 切片行（权威源）
            List<KnowledgeChunk> chunks = buildChunks(doc, kb, segments);
            chunkService.saveBatch(chunks);

            // ⑤ 再写 Milvus 向量（显式 id = vectorId，可幂等覆盖）
            writeVectors(kb, segments, chunks);

            // ⑥ 收尾
            doc.setChunkCount(chunks.size());
            setStatus(doc, DocumentStatusEnum.INDEXED);
            refreshKbStatus(kb.getId());
            log.info("文档索引完成 docId={}, chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("文档索引失败 docId={}", docId, e);
            markFailed(doc, e.getMessage());
            refreshKbStatus(doc.getKbId());
        } finally {
            // 无论成功失败都失效 BM25：失败也可能已经写了切片行（切片在前、向量在后），
            // 那种情况下切片的集合已经变了，索引不能再信（见 knowledge/23 的失效点清单）
            bm25IndexRegistry.invalidate(kb.getId());
        }
    }

    @Override
    public void submitRebuild(Long kbId, boolean full) {
        KnowledgeBase kb = baseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        }
        setKbStatus(kbId, KbStatusEnum.BUILDING);
        bm25IndexRegistry.invalidate(kbId);
        kbIndexExecutor.execute(() -> {
            try {
                if (full) {
                    // 全量：先删整个集合，从零重建（对账/换参数场景）
                    milvusStoreFactory.dropCollection(kb.getCollectionName());
                }
                List<KnowledgeDocument> docs = documentMapper.selectList(
                        new QueryWrapper<KnowledgeDocument>().eq("kbId", kbId));
                for (KnowledgeDocument doc : docs) {
                    indexDocument(doc.getId());
                }
                refreshKbStatus(kbId);
                log.info("知识库重建完成 kbId={}, full={}, docs={}", kbId, full, docs.size());
            } catch (Exception e) {
                log.error("知识库重建失败 kbId={}", kbId, e);
                setKbStatus(kbId, KbStatusEnum.FAILED);
            }
        });
    }

    @Override
    public void deleteVectors(Long kbId, List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        KnowledgeBase kb = baseMapper.selectById(kbId);
        if (kb == null) {
            return;
        }
        // 集合不存在就别去 getStore——否则会凭空建出一个空集合
        if (!milvusStoreFactory.collectionExists(kb.getCollectionName())) {
            return;
        }
        EmbeddingStore<TextSegment> store = milvusStoreFactory.getStore(kb.getCollectionName(), kb.getDimension());
        store.removeAll(vectorIds);
        bm25IndexRegistry.invalidate(kbId);
    }

    @Override
    public void refreshKbStatus(Long kbId) {
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new QueryWrapper<KnowledgeDocument>().eq("kbId", kbId));
        int docCount = docs.size();
        long chunkCount = chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>().eq("kbId", kbId));

        KbStatusEnum status;
        boolean anyIndexed = false;
        boolean anyProcessing = false;
        for (KnowledgeDocument doc : docs) {
            DocumentStatusEnum ds = parseStatus(doc.getStatus());
            if (ds != null && ds.isProcessing()) {
                anyProcessing = true;
            }
            if (ds == DocumentStatusEnum.INDEXED) {
                anyIndexed = true;
            }
        }
        if (docCount == 0) {
            status = KbStatusEnum.EMPTY;
        } else if (anyProcessing) {
            status = KbStatusEnum.BUILDING;
        } else {
            status = anyIndexed ? KbStatusEnum.READY : KbStatusEnum.FAILED;
        }

        KnowledgeBase update = new KnowledgeBase();
        update.setId(kbId);
        update.setStatus(status.name());
        update.setDocCount(docCount);
        update.setChunkCount((int) chunkCount);
        baseMapper.updateById(update);
    }

    @Override
    public List<String> listVectorIds(Long kbId) {
        return chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                        .select("vectorId")
                        .eq("kbId", kbId))
                .stream()
                .map(KnowledgeChunk::getVectorId)
                .toList();
    }

    private Document parse(KnowledgeDocument doc) {
        FileTypeEnum fileType;
        try {
            fileType = FileTypeEnum.valueOf(doc.getFileType());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未知文件类型: " + doc.getFileType());
        }
        if (fileType == FileTypeEnum.URL) {
            return documentParserFactory.parseUrl(doc.getSourceUrl());
        }
        try (InputStream in = fileStorageService.open(doc.getFilePath())) {
            return documentParserFactory.parseFile(fileType, in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "原始文件读取失败: " + e.getMessage());
        }
    }

    private List<KnowledgeChunk> buildChunks(KnowledgeDocument doc, KnowledgeBase kb, List<TextSegment> segments) {
        List<KnowledgeChunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            String content = segments.get(i).text();
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setKbId(kb.getId());
            chunk.setDocId(doc.getId());
            chunk.setChunkIndex(i);
            chunk.setVectorId(vectorId(doc.getId(), i, content));
            chunk.setContent(content);
            chunk.setCharCount(content.length());
            // tokenCount 暂留空：bge tokenizer 未向上层暴露，待后续用真实 tokenizer 实测（见 knowledge/22）
            chunks.add(chunk);
        }
        return chunks;
    }

    private void writeVectors(KnowledgeBase kb, List<TextSegment> segments, List<KnowledgeChunk> chunks) {
        List<TextSegment> segmentsWithMeta = new ArrayList<>(chunks.size());
        List<String> ids = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            Metadata metadata = new Metadata()
                    .put("kbId", chunk.getKbId())
                    .put("docId", chunk.getDocId())
                    .put("chunkIndex", chunk.getChunkIndex());
            segmentsWithMeta.add(TextSegment.from(chunk.getContent(), metadata));
            ids.add(chunk.getVectorId());
        }
        List<Embedding> embeddings = embeddingEngine.embedDocuments(segmentsWithMeta);

        EmbeddingStore<TextSegment> store = milvusStoreFactory.getStore(kb.getCollectionName(), kb.getDimension());
        store.addAll(ids, embeddings, segmentsWithMeta);
    }

    private void clearDocumentData(KnowledgeDocument doc, KnowledgeBase kb) {
        List<String> oldIds = chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                        .select("vectorId")
                        .eq("docId", doc.getId()))
                .stream()
                .map(KnowledgeChunk::getVectorId)
                .toList();
        if (!oldIds.isEmpty()) {
            try {
                deleteVectors(kb.getId(), oldIds);
            } catch (Exception e) {
                // 旧向量删不掉不致命：新切片会用同样的确定性 id 覆盖大部分旧向量
                log.warn("清理旧向量失败 docId={}", doc.getId(), e);
            }
            chunkMapper.delete(new QueryWrapper<KnowledgeChunk>().eq("docId", doc.getId()));
        }
    }

    /**
     * 确定性 UUID：同一「文档 + 位置 + 内容」永远得到同一个 id。
     *
     * <p>好处是重建时 Milvus 里是幂等覆盖而不是「删旧插新」；代价是内容变了 id 就变
     * （内容进了种子）。36 字符正好放进 Milvus 的 VarChar(36) 主键字段。
     */
    private static String vectorId(Long docId, int chunkIndex, String content) {
        String seed = docId + "#" + chunkIndex + "#" + SecureUtil.md5(content);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void setStatus(KnowledgeDocument doc, DocumentStatusEnum status) {
        doc.setStatus(status.name());
        documentMapper.updateById(doc);
    }

    private void markFailed(KnowledgeDocument doc, String message) {
        doc.setStatus(DocumentStatusEnum.FAILED.name());
        doc.setErrorMsg(StrUtil.maxLength(StrUtil.blankToDefault(message, "未知错误"), MAX_ERROR_LEN));
        documentMapper.updateById(doc);
    }

    private void setKbStatus(Long kbId, KbStatusEnum status) {
        KnowledgeBase update = new KnowledgeBase();
        update.setId(kbId);
        update.setStatus(status.name());
        baseMapper.updateById(update);
    }

    private static DocumentStatusEnum parseStatus(String status) {
        try {
            return status == null ? null : DocumentStatusEnum.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
