-- RAG 知识库域 4 张表（DDL 与 knowledge/10-数据模型总览.md 一一对应）
--
-- ⚠️ 列名必须用 camelCase，不能改成下划线：
--    application.yml 里 map-underscore-to-camel-case: false，
--    列名和 Java 属性名逐字符相同才能被 MyBatis-Plus 自动映射。
--    写下划线列名的话，selectById 返回的对象除主键外全是 null，且不报错。
--
-- 依赖 user 表所在库，先跑 sql/create_table.sql 建库。

USE `star_assistant`;

-- 知识库：检索的独立单元，每个库对应一个 Milvus 集合 star_kb_{id}
CREATE TABLE `knowledge_base`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库id',
    `name`           VARCHAR(80)  NOT NULL COMMENT '知识库名称',
    `description`    VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `categoryId`     BIGINT       DEFAULT NULL COMMENT '所属分类id，NULL 表示未分类',
    `embeddingModel` VARCHAR(64)  NOT NULL COMMENT '向量模型标识，检索前校验与入库一致',
    `dimension`      INT          NOT NULL COMMENT '向量维度，必须与模型一致',
    `collectionName` VARCHAR(128) NOT NULL COMMENT 'Milvus 集合名，规则 star_kb_{id}',
    `chunkSize`      INT          NOT NULL DEFAULT 400 COMMENT '切片长度（字符）',
    `chunkOverlap`   INT          NOT NULL DEFAULT 80 COMMENT '切片重叠（字符）',
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'EMPTY' COMMENT 'EMPTY/BUILDING/READY/FAILED',
    `docCount`       INT          NOT NULL DEFAULT 0 COMMENT '文档数（冗余计数）',
    `chunkCount`     INT          NOT NULL DEFAULT 0 COMMENT '切片数（冗余计数）',
    `userId`         BIGINT       NOT NULL COMMENT '创建人',
    `createTime`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 1 已删除',
    PRIMARY KEY (`id`),
    -- 由 id 派生本应天然唯一，加唯一索引是防代码 bug 导致两个库指向同一集合（向量混在一起且不报错）
    UNIQUE KEY `uk_collectionName` (`collectionName`),
    KEY `idx_userId_isDelete` (`userId`, `isDelete`),
    KEY `idx_categoryId` (`categoryId`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库';

-- 知识库分类树。⚠️ 故意不加 uk(userId, parentId, name)：逻辑删除会挡住「删掉再建同名分类」
CREATE TABLE `knowledge_category`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类id',
    `name`       VARCHAR(80) NOT NULL COMMENT '分类名称',
    `parentId`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类id，0 表示根分类',
    `level`      TINYINT     NOT NULL DEFAULT 1 COMMENT '层级，从 1 开始',
    `sortOrder`  INT         NOT NULL DEFAULT 0 COMMENT '同级排序，值小的在前',
    `userId`     BIGINT      NOT NULL COMMENT '创建人',
    `createTime` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete`   TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_userId_parentId` (`userId`, `parentId`, `isDelete`),
    KEY `idx_userId_name` (`userId`, `name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库分类';

-- 文档。contentHash 是增量索引的支点（正文 MD5，不是文件 MD5）
CREATE TABLE `knowledge_document`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档id',
    `kbId`        BIGINT       NOT NULL COMMENT '所属知识库id',
    `name`        VARCHAR(255) NOT NULL COMMENT '文档名称（原始文件名或标题）',
    `fileType`    VARCHAR(20)  NOT NULL COMMENT 'PDF/DOCX/TXT/MD/HTML/URL',
    `filePath`    VARCHAR(512) DEFAULT NULL COMMENT '原始文件落盘路径',
    `fileSize`    BIGINT       DEFAULT NULL COMMENT '文件大小（字节）',
    `sourceUrl`   VARCHAR(1024) DEFAULT NULL COMMENT 'fileType=URL 时的来源地址',
    `contentHash` CHAR(32)     DEFAULT NULL COMMENT '正文 MD5，增量索引的指纹',
    `charCount`   INT          DEFAULT NULL COMMENT '正文字符数',
    `chunkCount`  INT          NOT NULL DEFAULT 0 COMMENT '切出的切片数',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/PARSING/PARSED/INDEXING/INDEXED/FAILED/DELETING',
    `errorMsg`    VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    `userId`      BIGINT       NOT NULL COMMENT '上传人',
    `createTime`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_kbId_status` (`kbId`, `status`, `isDelete`),
    KEY `idx_kbId_contentHash` (`kbId`, `contentHash`),
    KEY `idx_userId_isDelete` (`userId`, `isDelete`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档';

-- 切片。⚠️ 没有 isDelete，删除是真删（派生数据；保留幽灵切片会污染 BM25 重建）
-- vectorId 是确定性 UUID，同时是 Milvus 主键（Milvus PK 字段为 VarChar(36)，正好匹配）
CREATE TABLE `knowledge_chunk`
(
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '切片id',
    `kbId`       BIGINT   NOT NULL COMMENT '所属知识库id（冗余，便于按库批量操作）',
    `docId`      BIGINT   NOT NULL COMMENT '所属文档id',
    `chunkIndex` INT      NOT NULL COMMENT '文档内序号，从 0 开始',
    `vectorId`   CHAR(36) NOT NULL COMMENT 'Milvus 主键，确定性 UUID',
    `content`    TEXT     DEFAULT NULL COMMENT '切片正文',
    `tokenCount` INT      DEFAULT NULL COMMENT 'token 数（实测，非估算）',
    `charCount`  INT      DEFAULT NULL COMMENT '字符数',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vectorId` (`vectorId`),
    KEY `idx_docId_chunkIndex` (`docId`, `chunkIndex`),
    KEY `idx_kbId` (`kbId`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档切片';
