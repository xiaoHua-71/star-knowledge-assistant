package com.xiaohua.star.service.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.star.ai.EmbeddingEngine;
import com.xiaohua.star.ai.MilvusStoreFactory;
import com.xiaohua.star.constant.UserConstant;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.mapper.KnowledgeDocumentMapper;
import com.xiaohua.star.mapper.UserMapper;
import com.xiaohua.star.model.dto.knowledge.KnowledgeBaseCreateRequest;
import com.xiaohua.star.model.entity.KnowledgeBase;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.enums.DocumentStatusEnum;
import com.xiaohua.star.model.enums.FileTypeEnum;
import com.xiaohua.star.model.eval.EvalCorpusFile;
import com.xiaohua.star.model.vo.KnowledgeBaseVO;
import com.xiaohua.star.model.vo.RagCorpusSummaryVO;
import com.xiaohua.star.service.FileStorageService;
import com.xiaohua.star.service.KbIndexService;
import com.xiaohua.star.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 评测语料库的初始化与导入。
 *
 * <p>用<b>专用用户 + 专用知识库</b>隔离评测数据，避免污染真实用户的知识库列表。
 * 导入走完整链路：落盘 → 写 {@code knowledge_document} → 同步 {@link KbIndexService#indexDocument}
 * （解析/切片/嵌入/Milvus/BM25 一个不落），只是不经过异步上传和轮询。</p>
 *
 * <p>已索引的文档直接跳过；语料是静态资产，内容变更需要 {@code full=true} 整库重建。
 * 库里存在语料目录之外的旧文档时，报告里会给出 {@code staleDocs} 提醒。</p>
 */
@Slf4j
@Service
public class EvalCorpusService {

    private static final String EVAL_USERNAME = "rag-eval";
    private static final String DEFAULT_DESCRIPTION = "离线评测语料库（自动维护，勿手动修改）";

    @Resource
    private RagEvalProperties properties;

    @Resource
    private UserMapper userMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeDocumentMapper documentMapper;

    @Resource
    private KnowledgeChunkMapper chunkMapper;

    @Resource
    private FileStorageService fileStorageService;

    @Resource
    private KbIndexService kbIndexService;

    @Resource
    private EmbeddingEngine embeddingEngine;

    @Resource
    private MilvusStoreFactory milvusStoreFactory;

    /**
     * 确保评测语料库可用并完成导入。
     *
     * @param full true 表示删掉旧评测库重建（换切片参数、语料内容变化、模型变化时用）
     */
    public RagCorpusSummaryVO ensureCorpus(boolean full) {
        Long userId = resolveEvalUserId();
        List<EvalCorpusFile> files = loadCorpusFiles();
        Set<String> corpusSources = files.stream().map(EvalCorpusFile::source).collect(java.util.stream.Collectors.toSet());

        KnowledgeBase kb = findKb(userId);
        if (kb != null && (full || needsRebuild(kb))) {
            log.info("重建评测知识库 kbId={} (full={})", kb.getId(), full);
            knowledgeBaseService.delete(kb.getId(), userId);
            kb = null;
        }
        if (kb == null) {
            kb = createKb(userId);
            log.info("已创建评测知识库 kbId={}, collection={}", kb.getId(), kb.getCollectionName());
        }

        Map<String, KnowledgeDocument> docsBySource = loadDocsBySource(kb.getId());
        int imported = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            EvalCorpusFile file = files.get(i);
            KnowledgeDocument doc = docsBySource.get(file.source());
            if (doc != null && DocumentStatusEnum.INDEXED.name().equals(doc.getStatus())) {
                skipped++;
                continue;
            }
            try {
                Long docId = doc == null ? importOne(kb, userId, file) : doc.getId();
                if (doc != null) {
                    kbIndexService.indexDocument(docId);
                }
                KnowledgeDocument after = documentMapper.selectById(docId);
                if (after == null || !DocumentStatusEnum.INDEXED.name().equals(after.getStatus())) {
                    String reason = after == null ? "文档行消失" : after.getErrorMsg();
                    failures.add(file.source() + " -> " + reason);
                } else {
                    imported++;
                }
            } catch (Exception e) {
                log.warn("语料导入失败 [{}]: {}", file.fileName(), e.getMessage(), e);
                failures.add(file.source() + " -> " + e.getMessage());
            }
            if ((i + 1) % 10 == 0) {
                log.info("评测语料导入进度 {}/{}", i + 1, files.size());
            }
        }

        Set<String> indexedSources = documentMapper.selectList(new QueryWrapper<KnowledgeDocument>()
                        .select("name")
                        .eq("kbId", kb.getId())
                        .eq("status", DocumentStatusEnum.INDEXED.name())
                        .eq("isDelete", 0))
                .stream().map(KnowledgeDocument::getName).collect(java.util.stream.Collectors.toSet());
        int staleDocs = (int) indexedSources.stream().filter(source -> !corpusSources.contains(source)).count();
        boolean complete = failures.isEmpty() && indexedSources.containsAll(corpusSources);
        int chunks = Math.toIntExact(chunkMapper.selectCount(
                new QueryWrapper<com.xiaohua.star.model.entity.KnowledgeChunk>().eq("kbId", kb.getId())));

        log.info("评测语料就绪：文件 {} / 已索引 {} / 切片 {} / 本次导入 {} / 跳过 {} / 陈旧 {} / 完整={}",
                files.size(), indexedSources.size(), chunks, imported, skipped, staleDocs, complete);
        return new RagCorpusSummaryVO(kb.getId(), kb.getCollectionName(), kb.getName(),
                files.size(), indexedSources.size(), chunks, imported, skipped, staleDocs, complete, failures);
    }

    /**
     * 评测固定使用配置里的用户；未配置时自动创建/复用 {@code rag-eval}。
     */
    public Long resolveEvalUserId() {
        if (properties.getUserId() != null) {
            if (userMapper.selectById(properties.getUserId()) == null) {
                throw new IllegalStateException("rag.eval.user-id 指向的用户不存在: " + properties.getUserId());
            }
            return properties.getUserId();
        }
        User existing = userMapper.selectOne(new QueryWrapper<User>()
                .eq("username", EVAL_USERNAME).last("limit 1"));
        if (existing != null) {
            return existing.getId();
        }
        User user = new User();
        user.setUsername(EVAL_USERNAME);
        // 这个账号不用于登录；密码给一个随机串即可，避免空值违反 NOT NULL
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setNickname("RAG 评测");
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        user.setIsDelete(0);
        if (userMapper.insert(user) != 1 || user.getId() == null) {
            throw new IllegalStateException("创建评测用户失败");
        }
        log.info("已创建评测用户 id={}", user.getId());
        return user.getId();
    }

    private KnowledgeBase findKb(Long userId) {
        return knowledgeBaseService.getOne(new QueryWrapper<KnowledgeBase>()
                .eq("userId", userId)
                .eq("name", properties.getCorpusKbName())
                .eq("isDelete", 0)
                .orderByDesc("id")
                .last("limit 1"));
    }

    private KnowledgeBase createKb(Long userId) {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName(properties.getCorpusKbName());
        request.setDescription(DEFAULT_DESCRIPTION);
        KnowledgeBaseVO vo = knowledgeBaseService.create(request, userId);
        return knowledgeBaseService.getById(vo.getId());
    }

    /**
     * 集合没了或模型换了，继续增量索引会「看起来成功、实际没向量」，必须整库重建。
     */
    private boolean needsRebuild(KnowledgeBase kb) {
        if (!embeddingEngine.modelId().equals(kb.getEmbeddingModel())
                || embeddingEngine.dimension() != kb.getDimension()) {
            log.warn("评测库模型不一致（库 {} / 当前 {}），将重建", kb.getEmbeddingModel(), embeddingEngine.modelId());
            return true;
        }
        try {
            return !milvusStoreFactory.collectionExists(kb.getCollectionName());
        } catch (Exception e) {
            log.warn("检查 Milvus 集合失败，按存在处理: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, KnowledgeDocument> loadDocsBySource(Long kbId) {
        Map<String, KnowledgeDocument> result = new HashMap<>();
        for (KnowledgeDocument doc : documentMapper.selectList(new QueryWrapper<KnowledgeDocument>()
                .eq("kbId", kbId).eq("isDelete", 0))) {
            result.put(doc.getName(), doc);
        }
        return result;
    }

    private Long importOne(KnowledgeBase kb, Long userId, EvalCorpusFile file) {
        byte[] body = file.body().getBytes(StandardCharsets.UTF_8);
        String filePath;
        try (InputStream in = new ByteArrayInputStream(body)) {
            filePath = fileStorageService.store(in, file.fileName(), kb.getId());
        } catch (IOException e) {
            throw new IllegalStateException("写入语料文件失败: " + e.getMessage(), e);
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kb.getId());
        // 文档名直接用来源 URL：检索结果里的 docName 就是它，评估匹配不再需要额外映射
        doc.setName(file.source());
        doc.setFileType(FileTypeEnum.TXT.name());
        doc.setFilePath(filePath);
        doc.setFileSize((long) body.length);
        doc.setSourceUrl(file.source());
        doc.setChunkCount(0);
        doc.setStatus(DocumentStatusEnum.PENDING.name());
        doc.setUserId(userId);
        if (documentMapper.insert(doc) != 1 || doc.getId() == null) {
            throw new IllegalStateException("写入语料文档行失败: " + file.source());
        }
        kbIndexService.indexDocument(doc.getId());
        return doc.getId();
    }

    private List<EvalCorpusFile> loadCorpusFiles() {
        Path dir = Paths.get(properties.getCorpusDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("语料目录不存在: " + dir.toAbsolutePath());
        }
        List<EvalCorpusFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.filter(p -> p.toString().toLowerCase().endsWith(".txt")).sorted().toList()) {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                files.add(EvalCorpusFile.parse(path.getFileName().toString(), raw));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取语料目录失败: " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("语料目录为空: " + dir.toAbsolutePath());
        }
        Set<String> seen = new HashSet<>();
        for (EvalCorpusFile file : files) {
            if (!seen.add(file.source())) {
                throw new IllegalStateException("语料来源重复: " + file.source());
            }
        }
        return files;
    }
}
