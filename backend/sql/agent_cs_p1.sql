-- =====================================================================
-- 智能客服 P1 增强（语义缓存 / 槽位填充 / 知识缺口 / badcase）
-- 目标库: forge-vue
-- 幂等: 建表 IF NOT EXISTS，可重复执行
-- =====================================================================
SET NAMES utf8mb4;

-- ① 语义缓存：只缓存「通用问答」，按 空间 + 渠道 分区，绝不跨用户命中
CREATE TABLE IF NOT EXISTS `agent_cs_semantic_cache` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '分区键:同空间同渠道才可能命中',
  `question_hash` CHAR(64) NOT NULL COMMENT '归一化问题的 SHA-256(精确匹配用)',
  `question` VARCHAR(500) NOT NULL COMMENT '归一化后的问题原文(近似匹配与运营查看)',
  `answer` TEXT NOT NULL COMMENT '出站过滤后的答案',
  `intent` VARCHAR(64) NULL COMMENT '命中时的意图编码(白名单校验用)',
  `hits` INT DEFAULT 0 COMMENT '命中次数',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  `last_hit_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssc_scope_hash` (`workspace_id`, `channel_code`, `question_hash`),
  KEY `idx_cssc_scope_time` (`workspace_id`, `channel_code`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服语义缓存';

-- ② 槽位填充进度：一个会话一份表单一条，填满后清理
CREATE TABLE IF NOT EXISTS `agent_cs_slot_progress` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL,
  `form_name` VARCHAR(64) NOT NULL COMMENT '表单名,对应配置里的 slot-forms key',
  `collected_json` TEXT NULL COMMENT '已收集槽位 {name: value}',
  `asking` VARCHAR(64) NULL COMMENT '当前正在追问的槽位,NULL 表示未开始或已填满',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssp_conv_form` (`conversation_id`, `form_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多轮槽位收集进度';

-- ③ 知识缺口：零命中按问题归并,运营补答案后置 FILLED
CREATE TABLE IF NOT EXISTS `agent_cs_knowledge_gap` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `question_hash` CHAR(64) NOT NULL,
  `question` VARCHAR(500) NOT NULL,
  `category` VARCHAR(64) NULL COMMENT '分类(取自意图编码,可空)',
  `priority` VARCHAR(16) DEFAULT 'P2' COMMENT 'P0/P1/P2,按命中次数自动升级',
  `hit_count` INT DEFAULT 1,
  `status` VARCHAR(16) DEFAULT 'OPEN' COMMENT 'OPEN/FILLED/IGNORED',
  `answer_id` BIGINT NULL COMMENT '补齐后关联的 FAQ id',
  `last_hit_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cskg_ws_hash` (`workspace_id`, `question_hash`),
  KEY `idx_cskg_status_hits` (`status`, `hit_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识缺口';

-- ④ badcase：来源四类,三个出口(采纳为知识 / 标记评测 / 忽略)
CREATE TABLE IF NOT EXISTS `agent_cs_badcase` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `source` VARCHAR(24) NOT NULL COMMENT 'CSAT_LOW / SEAT_IGNORE / ZERO_HIT / SENSITIVE_HIT',
  `conversation_id` BIGINT NULL,
  `ticket_id` BIGINT NULL,
  `suggestion_id` BIGINT NULL,
  `question` VARCHAR(500) NULL,
  `answer` VARCHAR(1000) NULL,
  `status` VARCHAR(24) DEFAULT 'PENDING' COMMENT 'PENDING/ADOPTED_KB/ADOPTED_EVAL/IGNORED',
  `operator` VARCHAR(64) NULL,
  `note` VARCHAR(500) NULL,
  `exported` CHAR(1) DEFAULT '0' COMMENT '是否已导出为评测用例 JSON',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csbc_status` (`workspace_id`, `status`, `id`),
  KEY `idx_csbc_source` (`workspace_id`, `source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服 badcase';
