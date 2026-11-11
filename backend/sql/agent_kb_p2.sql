-- ----------------------------
-- 知识库优化 P2(2026-10-20)
-- 1. agent_knowledge_document 新增 file_md5(同一知识库内按文件内容 MD5 去重)
-- 2. 新增 agent_kb_zero_hit_log(对话检索零命中日志,运营发现知识缺口)
-- 执行前请确认当前库为业务库(如 forge-vue)
-- ----------------------------

-- 1. 文档内容 MD5
ALTER TABLE `agent_knowledge_document`
    ADD COLUMN `file_md5` VARCHAR(32) NULL DEFAULT NULL COMMENT '文件内容MD5(同库去重)' AFTER `file_size`;

CREATE INDEX `idx_akd_kb_md5` ON `agent_knowledge_document` (`knowledge_id`, `file_md5`);

-- 2. 零命中查询日志
CREATE TABLE IF NOT EXISTS `agent_kb_zero_hit_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `agent_id`        BIGINT       NULL DEFAULT NULL COMMENT 'Agent ID',
    `session_id`      BIGINT       NULL DEFAULT NULL COMMENT '会话 ID',
    `user_id`         BIGINT       NULL DEFAULT NULL COMMENT '用户 ID',
    `original_query`  VARCHAR(500) NULL DEFAULT NULL COMMENT '用户原始问题',
    `rewritten_query` VARCHAR(500) NULL DEFAULT NULL COMMENT '改写后的检索查询(未改写时与原始问题一致)',
    `knowledge_ids`   VARCHAR(200) NULL DEFAULT NULL COMMENT '参与检索的知识库ID(逗号分隔)',
    `create_time`     DATETIME     NULL DEFAULT NULL COMMENT '发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_azhl_agent_time` (`agent_id`, `create_time`),
    KEY `idx_azhl_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识库零命中查询日志';
