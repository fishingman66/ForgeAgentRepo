-- ----------------------------
-- R2-A 摄取与索引一致性(2026-11-11)
-- 1. agent_kb_chunk:generation(代次)/vector_status(向量同步状态)/parent_offset(父块内偏移)/page_no/sheet_name
-- 2. agent_knowledge_document:active_generation(当前生效代次)/notice(处理提示)
-- 3. agent_knowledge:hide_parent_with_disabled_child(父块含禁用子块时的可见性策略)
-- 4. 索引:分块按 (document_id, generation) 过滤;补偿扫描按 (vector_status, update_time) 找积压
--
-- 幂等:MySQL 不支持 ADD COLUMN IF NOT EXISTS,统一用 information_schema + PREPARE 守卫,可重复执行
-- ----------------------------

-- 1. agent_kb_chunk
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `generation` int NOT NULL DEFAULT 1 COMMENT ''分块代次(重建时递增,检索只认文档当前代次)'' AFTER `page_label`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'generation');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `vector_status` varchar(20) NOT NULL DEFAULT ''SYNCED'' COMMENT ''向量同步状态(PENDING/SYNCED/FAILED/SKIPPED)'' AFTER `generation`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'vector_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `vector_retry_count` int NOT NULL DEFAULT 0 COMMENT ''向量写入补偿重试次数'' AFTER `vector_status`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'vector_retry_count');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `parent_offset_start` int DEFAULT NULL COMMENT ''子块在父块内的起始偏移(用于精确剔除被禁用内容)'' AFTER `parent_id`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'parent_offset_start');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `parent_offset_end` int DEFAULT NULL COMMENT ''子块在父块内的结束偏移'' AFTER `parent_offset_start`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'parent_offset_end');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `page_no` int DEFAULT NULL COMMENT ''页码(PDF/Excel 页标记解析)'' AFTER `page_label`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'page_no');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD COLUMN `sheet_name` varchar(100) DEFAULT NULL COMMENT ''工作表名(Excel)'' AFTER `page_no`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND COLUMN_NAME = 'sheet_name');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD INDEX `idx_kb_chunk_doc_gen` (`document_id`, `generation`)',
    'SELECT 1') FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND INDEX_NAME = 'idx_kb_chunk_doc_gen');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_kb_chunk` ADD INDEX `idx_kb_chunk_vsync` (`vector_status`, `create_time`)',
    'SELECT 1') FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_kb_chunk' AND INDEX_NAME = 'idx_kb_chunk_vsync');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. agent_knowledge_document
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge_document` ADD COLUMN `active_generation` int NOT NULL DEFAULT 1 COMMENT ''当前生效代次(重建成功后才切换)'' AFTER `chunk_count`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge_document' AND COLUMN_NAME = 'active_generation');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge_document` ADD COLUMN `notice` varchar(200) DEFAULT '''' COMMENT ''处理提示(如向量写入失败待补偿、扫描件需 OCR)'' AFTER `fail_reason`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge_document' AND COLUMN_NAME = 'notice');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. agent_knowledge
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge` ADD COLUMN `hide_parent_with_disabled_child` char(1) NOT NULL DEFAULT ''1'' COMMENT ''父块含禁用子块时:1整块不返回 0返回但挖除禁用片段'' AFTER `parent_chunk_size`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge' AND COLUMN_NAME = 'hide_parent_with_disabled_child');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. 存量数据无需回填:新列默认 generation=1 / vector_status=SYNCED / active_generation=1,
--    即"存量视为已就绪",避免上线瞬间补偿扫描把全库重嵌一遍。
