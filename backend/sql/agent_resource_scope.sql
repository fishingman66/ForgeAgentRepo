-- =====================================================================
-- 资源域模型双模式: 工具 / 数据源 / 模型三表增加 workspace_id
-- 目标库: forge-vue
-- 语义: workspace_id IS NULL = 平台共享(全空间可见,仅超管可改)
--       workspace_id 非空  = 空间私有(仅归属空间可见)
-- 存量数据: 新列默认 NULL,即全部保持平台共享,上线零行为变更
-- 幂等: 加列/加索引走 information_schema 判断,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_scope_add_column`;
DROP PROCEDURE IF EXISTS `forge_scope_add_index`;
DELIMITER $$
CREATE PROCEDURE `forge_scope_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @forge_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE forge_stmt FROM @forge_sql;
        EXECUTE forge_stmt;
        DEALLOCATE PREPARE forge_stmt;
    END IF;
END$$

CREATE PROCEDURE `forge_scope_add_index`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @forge_sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX ', p_ddl);
        PREPARE forge_stmt FROM @forge_sql;
        EXECUTE forge_stmt;
        DEALLOCATE PREPARE forge_stmt;
    END IF;
END$$
DELIMITER ;

CALL `forge_scope_add_column`('agent_tool', 'workspace_id',
    '`workspace_id` BIGINT NULL COMMENT ''归属工作空间(NULL=平台共享,非空=空间私有)'' AFTER `id`');
CALL `forge_scope_add_index`('agent_tool', 'idx_tool_ws',
    '`idx_tool_ws` (`workspace_id`, `del_flag`)');

CALL `forge_scope_add_column`('agent_datasource', 'workspace_id',
    '`workspace_id` BIGINT NULL COMMENT ''归属工作空间(NULL=平台共享,非空=空间私有)'' AFTER `id`');
CALL `forge_scope_add_index`('agent_datasource', 'idx_ds_ws',
    '`idx_ds_ws` (`workspace_id`, `del_flag`)');

CALL `forge_scope_add_column`('agent_model', 'workspace_id',
    '`workspace_id` BIGINT NULL COMMENT ''归属工作空间(NULL=平台共享,非空=空间私有)'' AFTER `id`');
CALL `forge_scope_add_index`('agent_model', 'idx_model_ws',
    '`idx_model_ws` (`workspace_id`, `del_flag`)');

DROP PROCEDURE IF EXISTS `forge_scope_add_column`;
DROP PROCEDURE IF EXISTS `forge_scope_add_index`;

-- 说明: 存量数据 workspace_id 均为 NULL,继续按「平台共享」生效;
--       若运维需要一键收紧(全部按私有解释),配置 agent.resource-sharing.enabled=false。
