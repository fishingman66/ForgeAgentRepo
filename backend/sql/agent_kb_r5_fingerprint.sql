-- ----------------------------
-- R2-5 配置指纹 + 差异重建 + 解析产物复用(2026-11-11)
-- 1. agent_knowledge:parse_fingerprint / chunk_fingerprint(改配置即重算,指纹变化 → 标脏)
-- 2. agent_knowledge_document:config_stale(配置过期标记) / parse_fingerprint(文档摄取时的解析指纹) / parsed_text(解析产物)
-- 复用条件:parsed_text 非空 且 文档 parse_fingerprint == 知识库当前解析指纹 → 重建只重切不重解析
-- ----------------------------

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge` ADD COLUMN `parse_fingerprint` varchar(64) DEFAULT NULL COMMENT ''解析管线指纹(sha256:管线版本+清洗规则)'' AFTER `clean_rules`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge' AND COLUMN_NAME = 'parse_fingerprint');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge` ADD COLUMN `chunk_fingerprint` varchar(64) DEFAULT NULL COMMENT ''分块配置指纹(sha256:单位+策略+大小+重叠+父子参数)'' AFTER `parse_fingerprint`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge' AND COLUMN_NAME = 'chunk_fingerprint');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge_document` ADD COLUMN `config_stale` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''配置过期(1=知识库口径已变,待差异重建)'' AFTER `notice`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge_document' AND COLUMN_NAME = 'config_stale');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge_document` ADD COLUMN `parse_fingerprint` varchar(64) DEFAULT NULL COMMENT ''摄取时的解析管线指纹(复用判定依据)'' AFTER `config_stale`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge_document' AND COLUMN_NAME = 'parse_fingerprint');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_knowledge_document` ADD COLUMN `parsed_text` mediumtext COMMENT ''解析+清洗后的正文(≤400 万字符才落,超长不落不阻断)'' AFTER `parse_fingerprint`',
    'SELECT 1') FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_knowledge_document' AND COLUMN_NAME = 'parsed_text');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
