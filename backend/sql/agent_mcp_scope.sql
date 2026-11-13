-- =====================================================================
-- 资源域模型补漏: MCP 服务器注册表增加 workspace_id
-- 目标库: forge-vue
-- 语义: workspace_id IS NULL = 平台共享(全空间可见,仅超管可改)
--       workspace_id 非空  = 空间私有(仅归属空间可见)
-- 存量数据: 新列默认 NULL,即全部保持平台共享,上线零行为变更
-- 幂等: 加列/加索引走 information_schema 判断,可重复执行
-- 说明: 沿用 agent_resource_scope.sql 的存储过程壳,过程名带 mcp 前缀避免重名冲突
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_mcp_add_column`;
DROP PROCEDURE IF EXISTS `forge_mcp_add_index`;
DELIMITER $$
CREATE PROCEDURE `forge_mcp_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CREATE PROCEDURE `forge_mcp_add_index`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
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

CALL `forge_mcp_add_column`('agent_mcp_server', 'workspace_id',
    '`workspace_id` BIGINT NULL COMMENT ''归属工作空间(NULL=平台共享,非空=空间私有)'' AFTER `id`');
CALL `forge_mcp_add_index`('agent_mcp_server', 'idx_mcp_ws',
    '`idx_mcp_ws` (`workspace_id`, `del_flag`)');

DROP PROCEDURE IF EXISTS `forge_mcp_add_column`;
DROP PROCEDURE IF EXISTS `forge_mcp_add_index`;
