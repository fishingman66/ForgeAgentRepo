-- =====================================================================
-- P3 / M1: L2 会话记忆滚动摘要
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断,可重复执行
-- 说明: summary_upto_msg_id 之前(含)的消息已被压缩进 summary_text,
--       上下文装配只注入该 id 之后的原始消息 + 摘要块
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_p3_m1_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_p3_m1_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CALL `forge_p3_m1_add_column`('agent_session', 'summary_text',
    '`summary_text` TEXT NULL COMMENT ''L2 滚动摘要:summary_upto_msg_id 及之前的对话要点'' AFTER `title`');
CALL `forge_p3_m1_add_column`('agent_session', 'summary_upto_msg_id',
    '`summary_upto_msg_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''L2 摘要水位:最后一条已压缩消息的 id'' AFTER `summary_text`');

DROP PROCEDURE IF EXISTS `forge_p3_m1_add_column`;
