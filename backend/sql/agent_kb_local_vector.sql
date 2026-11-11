-- =====================================================================
-- 本地开发向量列（MySQL 向量存储）
-- 目标库: forge-vue
-- 说明: 开发环境不装 Milvus 时，把向量直接存成分块表的一列，
--       检索在应用层算余弦。生产仍用 Milvus，两者通过
--       rag.knowledge.vector-store 切换，接口不变。
-- 幂等: 加列走 information_schema 判断，可重复执行
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `kb_add_vector_column`;
DELIMITER $$
CREATE PROCEDURE `kb_add_vector_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @kb_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE kb_stmt FROM @kb_sql;
        EXECUTE kb_stmt;
        DEALLOCATE PREPARE kb_stmt;
    END IF;
END$$
DELIMITER ;

CALL `kb_add_vector_column`('agent_kb_chunk', 'embedding',
    '`embedding` BLOB NULL COMMENT "float32 小端向量(384 维=1536 字节),仅 vector-store=mysql 时使用"');
CALL `kb_add_vector_column`('agent_kb_chunk', 'embedding_dim',
    '`embedding_dim` SMALLINT NULL COMMENT "向量维度,与当前配置不一致时检索跳过"');

DROP PROCEDURE IF EXISTS `kb_add_vector_column`;
