-- =====================================================================
-- P2 / C3: 长期记忆分层增强
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_p2_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_p2_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CALL `forge_p2_add_column`('agent_memory', 'fact_key',
    '`fact_key` VARCHAR(128) NULL COMMENT ''事实归一化键(冲突替代依据)'' AFTER `content`');
CALL `forge_p2_add_column`('agent_memory', 'importance',
    '`importance` INT NOT NULL DEFAULT 5 COMMENT ''重要性 1-10'' AFTER `fact_key`');
CALL `forge_p2_add_column`('agent_memory', 'hit_count',
    '`hit_count` INT NOT NULL DEFAULT 0 COMMENT ''注入命中次数'' AFTER `importance`');
CALL `forge_p2_add_column`('agent_memory', 'last_hit_at',
    '`last_hit_at` DATETIME NULL COMMENT ''最近一次注入时间'' AFTER `hit_count`');

DROP PROCEDURE IF EXISTS `forge_p2_add_column`;
