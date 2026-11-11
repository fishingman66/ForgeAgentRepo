-- =====================================================================
-- 智能客服 P3 增强（多 Agent 意图路由）
-- 目标库: forge-vue
-- 幂等: 建表 IF NOT EXISTS，可重复执行
-- 说明: 渠道级路由表——同一渠道里，「退款」问题交给售后专家 Agent、
--       「物流」问题交给订单 Agent。未命中任何路由时回落到渠道默认 agentId。
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_p3`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_p3`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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
DELIMITER ;

-- 会话是否已确定接待 Agent:首条消息判定一次后锁定,避免中途换 Agent 导致上下文断裂。
-- 不能用 agent_session_id 判断——它在建会话时就写入了,不代表已经对话过。
CALL `cs_add_column_p3`('agent_cs_conversation', 'agent_routed',
    '`agent_routed` CHAR(1) DEFAULT ''0'' COMMENT "是否已确定接待 Agent(1 已确定,不再路由)"');

DROP PROCEDURE IF EXISTS `cs_add_column_p3`;

CREATE TABLE IF NOT EXISTS `agent_cs_agent_route` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '空则对该空间所有渠道生效',
  `intent_code` VARCHAR(64) NOT NULL COMMENT 'A3 意图编码,如 RETURN_REFUND',
  `agent_id` BIGINT NOT NULL COMMENT '命中后使用的 Agent',
  `priority` INT DEFAULT 0 COMMENT '同意图多渠道匹配时取大者',
  `enabled` CHAR(1) DEFAULT '1',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csroute_scope` (`workspace_id`, `channel_code`, `intent_code`),
  KEY `idx_csroute_enabled` (`workspace_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服多 Agent 路由';
