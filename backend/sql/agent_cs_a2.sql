-- =====================================================================
-- 智能客服坐席辅助 A2(语音转写触发)建表扩展
-- 目标库: forge-vue
-- 幂等: 加列走 information_schema 判断，可重复执行
-- 说明: A1 建表时未区分触发来源；A2 起一条推荐要能追溯到「文本消息」还是
--       「某通电话的某条转写」，故补 source / call_id 两列，历史行默认 text。
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column_a2`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column_a2`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CALL `cs_add_column_a2`('agent_cs_seat_suggestion', 'source',
    '`source` VARCHAR(16) NOT NULL DEFAULT ''text'' COMMENT "触发来源 text/voice"');
CALL `cs_add_column_a2`('agent_cs_seat_suggestion', 'call_id',
    '`call_id` BIGINT NULL COMMENT "语音来源的通话主键(A2)，文本为空"');

DROP PROCEDURE IF EXISTS `cs_add_column_a2`;
