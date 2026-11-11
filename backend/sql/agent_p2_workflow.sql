-- =====================================================================
-- P2 / W10: 对话 × 工作流打通
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_p2w_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_p2w_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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
DELIMITER ;

-- 工作流触发模式:0 关闭(对话走 ReAct) / 1 每轮对话自动执行工作流 / 2 按意图路由(问题命中工具或知识时走工作流)
CALL `forge_p2w_add_column`('agent_info', 'workflow_trigger_mode',
    '`workflow_trigger_mode` CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''工作流触发模式(0关 1每轮自动 2按意图路由)'' AFTER `workflow_json`');

DROP PROCEDURE IF EXISTS `forge_p2w_add_column`;
