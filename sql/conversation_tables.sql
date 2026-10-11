-- 会话域 2 张表（DDL 与 knowledge/10-数据模型总览.md 一一对应）
--
-- ⚠️ 列名必须用 camelCase，不能改成下划线：
--    application.yml 里 map-underscore-to-camel-case: false，
--    列名和 Java 属性名逐字符相同才能被 MyBatis-Plus 自动映射。
--    写下划线列名的话，selectById 返回的对象除主键外全是 null，且不报错。
--
-- ⚠️ conversation_message 故意【没有】isDelete，它要「真删」：
--    截断重发时要把 (conversationId, seq) 腾出来重新占用；逻辑删会让旧行占着
--    uk_conversationId_seq，重发立刻撞唯一键。全局逻辑删只作用于【声明了 isDelete 的实体】，
--    所以本表不受影响——但这也意味着谁给它加上 isDelete 就会静默地把真删变成假删。别加！
--
-- 依赖 user 表所在库，先跑 sql/create_table.sql 建库。

USE `star_assistant`;

-- 会话：用户可见的「一次对话」容器。逻辑删（保留壳用于列表过滤与审计）。
CREATE TABLE `conversation`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话id',
    `userId`          BIGINT       NOT NULL COMMENT '所属用户',
    `title`           VARCHAR(255) NOT NULL DEFAULT '新会话' COMMENT '会话标题',
    `kbIds`           VARCHAR(512) DEFAULT NULL COMMENT '关联知识库id，逗号分隔，空表示不用 RAG',
    `pinned`          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶',
    `messageCount`    INT          NOT NULL DEFAULT 0 COMMENT '消息数（冗余，同时用于生成 seq）',
    `lastMessageTime` DATETIME     DEFAULT NULL COMMENT '最后一条消息时间（列表排序用）',
    `createTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_list` (`userId`, `isDelete`, `pinned`, `lastMessageTime`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='会话';

-- 会话消息。真删（无 isDelete），故 uk(conversationId, seq) 可在截断后被重新占用。
CREATE TABLE `conversation_message`
(
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '消息id',
    `conversationId` BIGINT      NOT NULL COMMENT '所属会话id',
    `runId`          BIGINT      DEFAULT NULL COMMENT '属于哪次 Agent 运行（M4 为 NULL）',
    `seq`            INT         NOT NULL COMMENT '会话内序号，从 1 开始，决定顺序（权威顺序，不用时间戳）',
    `role`           VARCHAR(20) NOT NULL COMMENT 'USER/ASSISTANT（本模块只写这两种）',
    `content`        MEDIUMTEXT  DEFAULT NULL COMMENT '正文',
    `toolCallId`     VARCHAR(64) DEFAULT NULL COMMENT 'TOOL 消息对应哪次工具调用',
    `toolCallsJson`  MEDIUMTEXT  DEFAULT NULL COMMENT 'ASSISTANT 消息里的工具调用请求（原始 JSON，仅供展示，绝不进 prompt）',
    `promptVisible`  TINYINT     NOT NULL DEFAULT 1 COMMENT '是否参与 prompt 重建：0 仅展示',
    `interrupted`    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否被中断截断',
    `tokenCount`     INT         DEFAULT NULL COMMENT 'token 数',
    `createTime`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversationId_seq` (`conversationId`, `seq`),
    KEY `idx_conversationId_createTime` (`conversationId`, `createTime`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='会话消息';
