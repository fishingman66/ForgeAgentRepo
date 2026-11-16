-- =====================================================================
-- 08方案 / B3: 长期记忆关键词召回通道(MySQL FULLTEXT ngram)
-- 目标库: forge-vue
-- 说明: agent_memory 的 content + fact_key 建 ngram 全文索引,
--       供 AgentMemoryMapper.searchByKeyword 三路融合里的关键词路使用。
--       ngram 是 MySQL 内置解析器,中文按 2-gram 切词,零外部依赖。
-- 幂等: 加索引走 information_schema.STATISTICS 判断,可重复执行
-- 回滚: ALTER TABLE agent_memory DROP INDEX idx_memory_keyword;
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `forge_p2_memory_add_fulltext`;
DELIMITER $$
CREATE PROCEDURE `forge_p2_memory_add_fulltext`()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'agent_memory'
          AND INDEX_NAME = 'idx_memory_keyword'
    ) THEN
        ALTER TABLE `agent_memory`
            ADD FULLTEXT INDEX `idx_memory_keyword` (`content`, `fact_key`) WITH PARSER ngram;
    END IF;
END$$
DELIMITER ;

CALL `forge_p2_memory_add_fulltext`();

DROP PROCEDURE IF EXISTS `forge_p2_memory_add_fulltext`;
