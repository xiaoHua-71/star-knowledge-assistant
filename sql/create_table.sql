-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS `star_assistant` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `star_assistant`;

-- 用户表
-- 相对 salary-leap 的差异：补上漏掉的 email 列（代码一直在读写它），去掉游戏字段 salary / direction
CREATE TABLE `user`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`   VARCHAR(50)  NOT NULL COMMENT '用户名',
    `email`      VARCHAR(100) DEFAULT NULL COMMENT '邮箱（邮箱注册用；密码注册留空）',
    `password`   VARCHAR(255) NOT NULL COMMENT '密码（加密存储）',
    `nickname`   VARCHAR(50)  DEFAULT NULL COMMENT '用户昵称',
    `avatar`     VARCHAR(512) DEFAULT NULL COMMENT '用户头像URL',
    `userRole`   VARCHAR(20)  DEFAULT 'user' COMMENT '用户角色（user/admin）',
    `createTime` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`   TINYINT      DEFAULT 0 COMMENT '逻辑删除（0-未删除，1-已删除）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    -- MySQL 的唯一索引允许多个 NULL，所以密码注册（email 为空）不会互相冲突
    UNIQUE KEY `uk_email` (`email`),
    KEY          `idx_createTime` (`createTime`),
    KEY          `idx_userRole` (`userRole`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';
