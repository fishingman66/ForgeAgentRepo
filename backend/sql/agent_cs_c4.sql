-- =====================================================================
-- 智能客服 C4: 治理与合规(多语种兜底 / 客服审计 / 合规菜单)
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断,建表 IF NOT EXISTS,菜单按 menu_id
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_c4`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_c4`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CALL `cs_add_column_c4`('agent_cs_channel', 'fallback_reply_en',
    '`fallback_reply_en` VARCHAR(500) NULL COMMENT "英文兜底话术"');
DROP PROCEDURE IF EXISTS `cs_add_column_c4`;

-- 客服合规审计(与平台操作审计分开,避免污染 sys_oper_log)
CREATE TABLE IF NOT EXISTS `agent_cs_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL,
  `channel_code` VARCHAR(64) NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `actor_type` VARCHAR(16) NOT NULL COMMENT 'VISITOR/SEAT/SYSTEM',
  `actor_id` VARCHAR(64) NULL,
  `detail` VARCHAR(1000) NULL,
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csal_ws_time` (`workspace_id`, `create_time`),
  KEY `idx_csal_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服合规审计';

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040008, '合规与模板', 1761400000000040001, 7, 'compliance',
       'customerservice/Compliance', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:compliance:list', 'lock', null, null, 1, now(), 'PII / 留存 / 擦除 / 客服模板 / 多语言'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040008);
