-- Agent 域 3 张表（DDL 与 knowledge/10-数据模型总览.md 3.3 节一一对应）
--
-- ⚠️ 列名必须用 camelCase，不能改成下划线：
--    application.yml 里 map-underscore-to-camel-case: false，
--    列名和 Java 属性名逐字符相同才能被 MyBatis-Plus 自动映射。
--
-- ⚠️ 这三张表【都没有】isDelete，记录永不逻辑删除：
--    agent_run / agent_step / agent_event 是审计数据——「这次回答是怎么产生的」唯一能查的证据。
--    用户删会话时内容可清，但审计记录应保留（或归档），否则出问题无法复盘。
--
-- M8 只建这 3 张；agent_todo（计划项）要等「计划/todo」功能落地再建（当前没有 todo_update 事件）。
--
-- 依赖 user 表所在库，先跑 sql/create_table.sql 建库。

USE `star_assistant`;

-- 一次运行（用户提问 → 得到回答）。
CREATE TABLE `agent_run`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '运行id',
    `conversationId`  BIGINT        NOT NULL COMMENT '所属会话id',
    `userId`          BIGINT        NOT NULL COMMENT '发起人',
    `status`          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/RUNNING/SUSPENDED/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED',
    `interruptType`   VARCHAR(32)   DEFAULT NULL
        COMMENT 'USER_STOP/NEW_MESSAGE/SERVER_RESTART/APPROVAL_REQUIRED',
    `stepNo`          INT           NOT NULL DEFAULT 0 COMMENT '当前轮次',
    `maxRounds`       INT           NOT NULL DEFAULT 10 COMMENT '最大轮次护栏',
    `planVersion`     INT           NOT NULL DEFAULT 0 COMMENT 'todo 计划版本号',
    `inputMessageId`  BIGINT        DEFAULT NULL COMMENT '触发本次运行的用户消息id',
    `outputMessageId` BIGINT        DEFAULT NULL COMMENT '最终助手消息id',
    `errorMsg`        VARCHAR(1024) DEFAULT NULL,
    `tokenIn`         INT           NOT NULL DEFAULT 0 COMMENT '累计输入 token',
    `tokenOut`        INT           NOT NULL DEFAULT 0 COMMENT '累计输出 token',
    `startTime`       DATETIME      DEFAULT NULL,
    `endTime`         DATETIME      DEFAULT NULL,
    `createTime`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conversationId_createTime` (`conversationId`, `createTime`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='Agent 运行';

-- 执行步骤（状态机的核心）：审计 + 重放 + 幂等防线。
CREATE TABLE `agent_step`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '步骤id',
    `runId`           BIGINT        NOT NULL COMMENT '所属运行id',
    `stepNo`          INT           NOT NULL COMMENT '第几轮',
    `seq`             INT           NOT NULL COMMENT '运行内自增序号，决定重放顺序',
    `type`            VARCHAR(20)   NOT NULL COMMENT 'LLM_CALL/TOOL_CALL/TOOL_RESULT',
    `status`          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/INTERRUPTED',
    `toolCallId`      VARCHAR(64)   DEFAULT NULL COMMENT '模型给的调用id',
    `toolName`        VARCHAR(64)   DEFAULT NULL,
    `argumentsJson`   MEDIUMTEXT    DEFAULT NULL COMMENT '工具入参（原始 JSON）',
    `toolResultJson`  MEDIUMTEXT    DEFAULT NULL COMMENT '工具结果（重放时直接用）',
    `idempotencyKey`  VARCHAR(128)  DEFAULT NULL COMMENT '副作用工具的幂等键',
    `sideEffect`      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否改变系统状态',
    `requireApproval` TINYINT       NOT NULL DEFAULT 0 COMMENT '是否需要人工确认',
    `replayed`        TINYINT       NOT NULL DEFAULT 0 COMMENT '本次结果是否来自重放',
    `requestJson`     MEDIUMTEXT    DEFAULT NULL COMMENT 'LLM 调用入参快照',
    `responseJson`    MEDIUMTEXT    DEFAULT NULL COMMENT 'LLM 响应快照',
    `errorMsg`        VARCHAR(1024) DEFAULT NULL,
    `elapsedMs`       BIGINT        DEFAULT NULL,
    `startTime`       DATETIME(3)   DEFAULT NULL,
    `endTime`         DATETIME(3)   DEFAULT NULL,
    `createTime`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runId_seq` (`runId`, `seq`),
    UNIQUE KEY `uk_idempotencyKey` (`idempotencyKey`),
    KEY `idx_runId_stepNo` (`runId`, `stepNo`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='Agent 执行步骤';

-- SSE 事件流（断线回放用）。自增 id 直接当 SSE 的 event id。
-- ⚠️ message_delta（逐 token 增量）【不落这张表】——一次回答 500 token 就是 500 行纯冗余，
--    重连靠 message_snapshot 补偿（见 knowledge/10、knowledge/31）。
CREATE TABLE `agent_event`
(
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '事件id，同时用作 SSE 的 event id',
    `runId`          BIGINT      NOT NULL COMMENT '所属运行id',
    `conversationId` BIGINT      NOT NULL COMMENT '所属会话id',
    `eventType`      VARCHAR(32) NOT NULL
        COMMENT 'run_start/todo_update/tool_call/tool_result/interrupt/run_end/error/message_snapshot',
    `dataJson`       MEDIUMTEXT  NOT NULL COMMENT '事件体（原始 JSON）',
    `createTime`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_runId_id` (`runId`, `id`),
    KEY `idx_conversationId_id` (`conversationId`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='Agent 事件（SSE 回放用）';
