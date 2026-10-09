package com.xiaohua.star.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.star.model.dto.knowledge.KnowledgeDocumentQueryRequest;
import com.xiaohua.star.model.entity.KnowledgeDocument;
import com.xiaohua.star.model.vo.KnowledgeDocumentVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档服务：上传、网页入库、列表、重命名、删除、单篇重建。
 *
 * <p>上传与重建都是<b>异步</b>：接口立刻返回（文档 status=PENDING），真正的解析/向量化
 * 交给 {@link KbIndexService}。前端按 status 轮询。
 */
public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    KnowledgeDocumentVO upload(Long kbId, MultipartFile file, Long userId);

    KnowledgeDocumentVO addUrl(Long kbId, String url, Long userId);

    IPage<KnowledgeDocumentVO> listPage(Long kbId, KnowledgeDocumentQueryRequest request, Long userId);

    boolean rename(Long docId, String name, Long userId);

    /**
     * 删除文档：先删 Milvus 向量，再删切片行，最后删文档行（顺序不能反，见 knowledge/10）
     */
    boolean delete(Long docId, Long userId);

    /**
     * 重新索引单个文档（异步），用于失败重试
     */
    boolean reindex(Long docId, Long userId);

    KnowledgeDocument getOwnedOrThrow(Long docId, Long userId);

    KnowledgeDocumentVO toVO(KnowledgeDocument document);
}
