-- =====================================================================
-- P3 / M2': 长期记忆语义向量(MySQL 向量列)
-- 目标库: forge-vue
-- 说明: 与 agent_kb_chunk 的本地向量方案同构 —— 向量按 float32 小端存 BLOB,
--       检索在应用层算余弦,不新增表、不新增数据源。
--       embedding 列不进实体默认 SELECT(@TableField(select=false)),
--       只在向量检索时显式 select,避免列表查询把响应撑爆。
-- 幂等: 加列走 information_schema 判断,可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_p3_m2_add_column`;
DELIMITER $$
CREATE PROCEDURE `forge_p3_m2_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CALL `forge_p3_m2_add_column`('agent_memory', 'embedding',
    '`embedding` BLOB NULL COMMENT ''float32 小端向量,仅 memory.vector.backend=mysql 时使用'' AFTER `content`');
CALL `forge_p3_m2_add_column`('agent_memory', 'embedding_dim',
    '`embedding_dim` SMALLINT NULL COMMENT ''向量维度,与当前模型不一致时检索跳过'' AFTER `embedding`');
CALL `forge_p3_m2_add_column`('agent_memory', 'embedding_model',
    '`embedding_model` VARCHAR(64) NULL COMMENT ''向量化模型标识,换模型后旧向量失效'' AFTER `embedding_dim`');

DROP PROCEDURE IF EXISTS `forge_p3_m2_add_column`;
