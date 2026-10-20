-- 可观测性域 2 张表（DDL 与 knowledge/10-数据模型总览.md 3.4 节一一对应）
--
-- ⚠️ 列名必须用 camelCase（application.yml 里 map-underscore-to-camel-case: false）
--
-- ⚠️ 两张表都【没有】isDelete：日志只追加，不参与业务逻辑，长期保留用于分析。
--
-- 依赖 user 表所在库，先跑 sql/create_table.sql 建库。

USE `star_assistant`;

-- 模型调用日志：一次 LLM 调用一行。
CREATE TABLE `ai_call_log`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `conversationId`   BIGINT        DEFAULT NULL COMMENT '所属会话',
    `runId`            BIGINT        DEFAULT NULL COMMENT '所属运行',
    `messageId`        BIGINT        DEFAULT NULL COMMENT '产出哪条消息（当前版本留空）',
    `userId`           BIGINT        DEFAULT NULL,
    `scene`            VARCHAR(32)   NOT NULL COMMENT 'CHAT/QUERY_REWRITE/TOOL_PLAN/TITLE_GEN',
    `provider`         VARCHAR(32)   NOT NULL COMMENT 'deepseek / dashscope / ...',
    `modelName`        VARCHAR(64)   NOT NULL,
    `streaming`        TINYINT       NOT NULL DEFAULT 0,
    `requestJson`      MEDIUMTEXT    DEFAULT NULL COMMENT '请求快照（落库前截断）',
    `responseJson`     MEDIUMTEXT    DEFAULT NULL COMMENT '响应快照',
    `promptTokens`     INT           DEFAULT NULL,
    `completionTokens` INT           DEFAULT NULL,
    `totalTokens`      INT           DEFAULT NULL,
    `costYuan`         DECIMAL(12, 6) DEFAULT NULL COMMENT '成本（元），保留 6 位小数；价格未配置时为 NULL',
    `firstTokenMs`     BIGINT        DEFAULT NULL COMMENT '首 token 延迟（仅流式）',
    `elapsedMs`        BIGINT        DEFAULT NULL COMMENT '总耗时',
    `success`          TINYINT       NOT NULL DEFAULT 1,
    `errorMsg`         VARCHAR(1024) DEFAULT NULL,
    `createTime`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_runId` (`runId`),
    KEY `idx_conversationId_createTime` (`conversationId`, `createTime`),
    KEY `idx_scene_createTime` (`scene`, `createTime`),
    KEY `idx_userId_createTime` (`userId`, `createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='模型调用日志';

-- 工具调用日志：每次真实执行 / 每次结果重放各一行。
-- ⚠️ idempotencyKey 是【普通索引】不是唯一索引：重放时要能留下第二条日志（replayed=1）。
CREATE TABLE `ai_tool_log`
(
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `runId`          BIGINT        DEFAULT NULL COMMENT '所属运行',
    `stepId`         BIGINT        DEFAULT NULL COMMENT '对应的 agent_step.id',
    `userId`         BIGINT        DEFAULT NULL,
    `toolName`       VARCHAR(64)   NOT NULL,
    `source`         VARCHAR(32)   NOT NULL COMMENT 'LOCAL/LOCAL_QUERY/INTERNAL/...',
    `sideEffect`     TINYINT       NOT NULL DEFAULT 0,
    `argumentsJson`  MEDIUMTEXT    DEFAULT NULL,
    `resultJson`     MEDIUMTEXT    DEFAULT NULL,
    `success`        TINYINT       NOT NULL DEFAULT 1,
    `errorMsg`       VARCHAR(1024) DEFAULT NULL,
    `elapsedMs`      BIGINT        DEFAULT NULL,
    `replayed`       TINYINT       NOT NULL DEFAULT 0 COMMENT '结果来自重放而非重新执行',
    `idempotencyKey` VARCHAR(128)  DEFAULT NULL,
    `createTime`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_runId` (`runId`),
    KEY `idx_toolName_createTime` (`toolName`, `createTime`),
    KEY `idx_idempotencyKey` (`idempotencyKey`),
    KEY `idx_userId_createTime` (`userId`, `createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='工具调用日志';
