-- ----------------------------
-- R2-3 分块口径与策略(2026-11-11)
-- 1. agent_knowledge:chunk_unit(按字符/按 token)、split_strategy(结构感知/定长)
-- 2. agent_kb_chunk:heading_path(所属章节路径,便于按章节分组与过滤)
--
-- 幂等:information_schema + PREPARE 守卫,可重复执行
-- 存量数据无需回填:新列默认 chunk_unit='char' / split_strategy='structure',
-- 与升级前的行为完全一致(存量库分块大小仍按字符解释)。
-- ----------------------------

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge` ADD COLUMN `chunk_unit` varchar(10) NOT NULL DEFAULT ''char'' COMMENT ''分块计价单位(char字符/token估算)'' AFTER `chunk_overlap`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge' AND COLUMN_NAME = 'chunk_unit');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge` ADD COLUMN `split_strategy` varchar(20) NOT NULL DEFAULT ''structure'' COMMENT ''分块策略(structure结构感知/fixed定长对照)'' AFTER `chunk_unit`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge' AND COLUMN_NAME = 'split_strategy');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `heading_path` varchar(500) DEFAULT NULL COMMENT ''所属章节路径(如 部署 > 数据库),无标题为 NULL'' AFTER `sheet_name`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'heading_path');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
