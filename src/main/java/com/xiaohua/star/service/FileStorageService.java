package com.xiaohua.star.service;

import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.config.KnowledgeProperties;
import com.xiaohua.star.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 上传文件的落盘与读取。
 *
 * <p>为什么要存原始文件：增量索引要「改了哪篇只重建哪篇」，重建时得能重新解析，
 * 不能每次让用户重传（见 knowledge/24）。所以 knowledge_document.filePath 指向这里。
 *
 * <p>落盘路径：{@code {storage-dir}/{kbId}/{uuid}.{ext}}。<b>不沿用原始文件名</b>——
 * 原始文件名可能含路径分隔符（目录穿越）、超长、中文在部分文件系统上有编码问题，
 * 用 uuid 更省心；原始名只作为展示用的 knowledge_document.name。
 */
@Slf4j
@Service
public class FileStorageService {

    private final KnowledgeProperties properties;

    public FileStorageService(KnowledgeProperties properties) {
        this.properties = properties;
    }

    /**
     * 把上传文件写到磁盘，返回<b>相对 storage-dir 的路径</b>（存进 filePath）。
     */
    public String store(MultipartFile file, Long kbId) {
        try (InputStream in = file.getInputStream()) {
            return store(in, file.getOriginalFilename(), kbId);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 从输入流落盘（离线评测导入语料等程序化场景用）。
     *
     * <p>仍然沿用 uuid 命名，不信任原始文件名——原始名只作为展示用的
     * {@code knowledge_document.name}。</p>
     */
    public String store(InputStream in, String originalFilename, Long kbId) {
        String ext = extensionOf(originalFilename);
        String relative = kbId + "/" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return relative;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 读取已落盘的文件（重建时用）。
     */
    public InputStream open(String relativePath) {
        Path path = resolve(relativePath);
        if (!Files.exists(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "原始文件不存在，无法重建: " + relativePath);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件读取失败: " + e.getMessage());
        }
    }

    public void delete(String relativePath) {
        if (relativePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // 文件删不掉不该挡住业务删除，记日志即可（对账时可以清理孤儿文件）
            log.warn("删除原始文件失败: {}", relativePath, e);
        }
    }

    private Path resolve(String relativePath) {
        return Path.of(properties.getStorageDir()).resolve(relativePath).normalize();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
