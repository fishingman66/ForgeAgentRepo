-- =====================================================================
-- 智能客服 P0 增强（差距分析方案 P0 六项）
-- 目标库: forge-vue
-- 幂等: 加列/加索引走 information_schema 判断，建表 IF NOT EXISTS
-- 说明: ① 转人工关键词按渠道可配；② 敏感词词库（平台级 + 空间级）；
--       ③ 会话交接摘要；④ 工单 SLA 扫描索引。
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_p0`;
DROP PROCEDURE IF EXISTS `cs_add_index_p0`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_p0`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @cs_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE cs_stmt FROM @cs_sql;
        EXECUTE cs_stmt;
        DEALLOCATE PREPARE cs_stmt;
    END IF;
END$$

CREATE PROCEDURE `cs_add_index_p0`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @cs_sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX ', p_ddl);
        PREPARE cs_stmt FROM @cs_sql;
        EXECUTE cs_stmt;
        DEALLOCATE PREPARE cs_stmt;
    END IF;
END$$
DELIMITER ;

-- ① 渠道级转人工关键词(空则用平台默认 agent.customer-service.handoff-keywords)
CALL `cs_add_column_p0`('agent_cs_channel', 'handoff_keywords',
    '`handoff_keywords` VARCHAR(500) NULL COMMENT "转人工关键词(逗号分隔),空则用平台默认"');

-- ④ SLA 扫描按状态 + 更新时间过滤,避免全表扫
CALL `cs_add_index_p0`('agent_cs_ticket', 'idx_csticket_sla',
    '`idx_csticket_sla` (`status`, `update_time`)');

DROP PROCEDURE IF EXISTS `cs_add_column_p0`;
DROP PROCEDURE IF EXISTS `cs_add_index_p0`;

-- ② 敏感词词库(workspace_id 为空=平台级,对所有空间生效;severity 决定处置方式)
CREATE TABLE IF NOT EXISTS `agent_cs_sensitive_word` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL COMMENT '归属空间(NULL=平台级)',
  `word` VARCHAR(100) NOT NULL COMMENT '词条',
  `severity` VARCHAR(16) DEFAULT 'BLOCK' COMMENT 'BLOCK 拦截 / MASK 打码 / WARN 仅告警',
  `remark` VARCHAR(255) NULL,
  `enabled` CHAR(1) DEFAULT '1',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssw_ws_word` (`workspace_id`, `word`),
  KEY `idx_cssw_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服敏感词库';

INSERT INTO agent_cs_sensitive_word (workspace_id, word, severity, remark, enabled, create_time)
SELECT NULL, '竞品A', 'MASK', '示例:竞品名打码(生产按实际词库维护)', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_cs_sensitive_word WHERE workspace_id IS NULL AND word='竞品A');

-- ③ 会话交接摘要(转人工时异步生成,坐席接单先看摘要)
CREATE TABLE IF NOT EXISTS `agent_cs_handoff_summary` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `ticket_id` BIGINT NULL,
  `summary_json` TEXT NULL COMMENT '结构化摘要(oneLine/intent/emotion/tried/pending/nextStep/opening)',
  `source_version` INT DEFAULT 0 COMMENT '基于第几条消息生成的摘要,客户发新消息后 +1 触发重算',
  `degraded` CHAR(1) DEFAULT '0' COMMENT '是否降级为规则摘要 1/0',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cshs_conv` (`conversation_id`),
  KEY `idx_cshs_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席交接摘要';
