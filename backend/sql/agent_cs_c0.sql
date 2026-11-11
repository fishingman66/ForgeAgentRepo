-- =====================================================================
-- 智能客服模块（S0+C0+C1）建表与存量表扩展
-- 目标库: forge-vue
-- 幂等: 建表 CREATE TABLE IF NOT EXISTS；加列/加索引走 information_schema 判断，可重复执行
-- 归属: forge-customer-service 模块；表前缀 agent_cs_ 保留（数据库层惯例）
-- 说明: 表存在与否不影响主应用启动；agent.customer-service.enabled=false 时无客服端点
-- =====================================================================
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `cs_add_column`;
DROP PROCEDURE IF EXISTS `cs_add_index`;
DELIMITER $$
CREATE PROCEDURE `cs_add_column`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
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

CREATE PROCEDURE `cs_add_index`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @cs_sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX ', p_ddl);
        PREPARE cs_stmt FROM @cs_sql;
        EXECUTE cs_stmt;
        DEALLOCATE PREPARE cs_stmt;
    END IF;
END$$
DELIMITER ;

-- ---------------- 客服渠道 / 端点 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_channel` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '端点编码,全局唯一',
  `channel_type` VARCHAR(32) NOT NULL DEFAULT 'widget' COMMENT 'widget/h5/wechat_mp/wecom/openapi',
  `workspace_id` BIGINT NOT NULL COMMENT '归属空间,免登录链路 callerWorkspaceId',
  `agent_id` BIGINT NOT NULL COMMENT '客服机器人 AgentID',
  `owner_user_id` BIGINT NOT NULL COMMENT '渠道服务账号,仅占位 agent_session.user_id',
  `api_key_id` BIGINT NULL COMMENT '关联 agent_api_key',
  `knowledge_ids` VARCHAR(500) NULL COMMENT '允许检索的知识库ID,逗号分隔',
  `rate_limit` INT DEFAULT 60 COMMENT '每分钟请求数',
  `cors_allowlist` VARCHAR(500) NULL COMMENT '嵌入域名白名单,逗号分隔',
  `session_mode` VARCHAR(16) DEFAULT 'continuous' COMMENT 'continuous/per_message',
  `fallback_reply` VARCHAR(500) NULL COMMENT '兜底话术,生产必填',
  `handoff_policy` VARCHAR(32) DEFAULT 'keyword' COMMENT 'keyword/low_confidence/zero_hit/manual',
  `locale` VARCHAR(16) DEFAULT 'zh-CN',
  `status` CHAR(1) DEFAULT '0' COMMENT '0启用 1停用',
  `del_flag` BIGINT DEFAULT 0,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csc_code` (`channel_code`),
  KEY `idx_csc_ws` (`workspace_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服渠道端点';

-- ---------------- 访客 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_visitor` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(64) NOT NULL,
  `external_user_id` VARCHAR(128) NOT NULL COMMENT '渠道内唯一标识',
  `nickname` VARCHAR(128) NULL,
  `consent_memory` CHAR(1) DEFAULT '0',
  `consent_export` CHAR(1) DEFAULT '0',
  `last_active_at` DATETIME NULL,
  `erased_at` DATETIME NULL COMMENT '隐私擦除时间,软标记',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csv_ext` (`channel_code`, `external_user_id`),
  KEY `idx_csv_active` (`channel_code`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服访客';

-- ---------------- 客服会话（关联 agent_session） ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `agent_session_id` BIGINT NOT NULL COMMENT 'agent_session.id',
  `visitor_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL,
  `ticket_id` BIGINT NULL,
  `status` VARCHAR(24) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/WAITING_AGENT/HUMAN_SERVING/ENDED',
  `last_visitor_at` DATETIME NULL,
  `last_seat_at` DATETIME NULL,
  `csat_invited_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csc_session` (`agent_session_id`),
  KEY `idx_csc_visitor` (`visitor_id`, `status`),
  KEY `idx_csc_ws_status` (`workspace_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话';

-- ---------------- 工单（七态） ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_ticket` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `ticket_no` VARCHAR(32) NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `session_id` BIGINT NOT NULL,
  `visitor_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL,
  `title` VARCHAR(200) NULL,
  `category` VARCHAR(32) DEFAULT 'CONSULT',
  `priority` VARCHAR(16) DEFAULT 'NORMAL',
  `status` VARCHAR(24) NOT NULL DEFAULT 'AI_SERVING',
  `assignee_id` BIGINT NULL,
  `seat_group` VARCHAR(64) NULL,
  `handoff_reason` VARCHAR(200) NULL,
  `resolve_note` VARCHAR(1000) NULL,
  `reopen_count` INT DEFAULT 0,
  `first_response_ms` BIGINT NULL COMMENT '坐席首响毫秒',
  `bot_first_token_ms` BIGINT NULL COMMENT '机器人首 token 毫秒',
  `waiting_since` DATETIME NULL,
  `resolved_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_no` (`ticket_no`),
  KEY `idx_cst_conv_active` (`conversation_id`, `status`),
  KEY `idx_cst_status_wait` (`status`, `waiting_since`),
  KEY `idx_cst_assignee` (`assignee_id`, `status`),
  KEY `idx_cst_ws_time` (`workspace_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单';

-- ---------------- 工单事件轨迹（只追加） ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_ticket_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `ticket_id` BIGINT NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `from_status` VARCHAR(24) NULL,
  `to_status` VARCHAR(24) NULL,
  `actor_type` VARCHAR(16) NOT NULL COMMENT 'VISITOR/SEAT/BOT/SYSTEM',
  `actor_id` VARCHAR(64) NULL,
  `note` VARCHAR(1000) NULL,
  `trace_id` VARCHAR(64) NULL,
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cste_ticket` (`ticket_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单事件轨迹';

-- ---------------- 坐席 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_seat` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT 'sys_user.userId',
  `workspace_id` BIGINT NOT NULL,
  `seat_group` VARCHAR(64) DEFAULT 'default',
  `skills` VARCHAR(500) NULL COMMENT '逗号分隔技能标签',
  `max_load` INT DEFAULT 5,
  `current_load` INT DEFAULT 0,
  `online` CHAR(1) DEFAULT '0',
  `role` VARCHAR(16) DEFAULT 'AGENT' COMMENT 'AGENT/LEAD/ADMIN',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_css_user` (`user_id`),
  KEY `idx_css_ws_group` (`workspace_id`, `seat_group`, `online`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服坐席';

-- ---------------- 排队 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_queue` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `ticket_id` BIGINT NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `required_skill` VARCHAR(64) NULL,
  `priority` VARCHAR(16) DEFAULT 'NORMAL',
  `enqueued_at` DATETIME NULL,
  `deadline_at` DATETIME NULL,
  `escalate_count` INT DEFAULT 0,
  `state` VARCHAR(16) DEFAULT 'WAITING' COMMENT 'WAITING/ASSIGNED/TIMEOUT',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csq_ticket` (`ticket_id`),
  KEY `idx_csq_wait` (`state`, `enqueued_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服排队';

-- ---------------- 消息幂等回执 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_message_receipt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT NOT NULL,
  `client_msg_id` VARCHAR(64) NOT NULL,
  `message_id` BIGINT NULL,
  `acceptance` VARCHAR(16) NOT NULL COMMENT 'ACCEPTED/REJECTED/UNKNOWN',
  `run_id` VARCHAR(64) NULL,
  `reply_content` TEXT NULL COMMENT '本轮回复快照,重发时原样返回',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csr_dedup` (`session_id`, `client_msg_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客消息幂等回执';

-- ---------------- 会话满意度 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_csat` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `score` INT NULL,
  `comment` VARCHAR(1000) NULL,
  `tags` VARCHAR(255) NULL,
  `invited_at` DATETIME NULL,
  `submitted_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cssat_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话满意度';

-- ---------------- 质检规则 / 结果 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_quality_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL,
  `rule_type` VARCHAR(16) DEFAULT 'KEYWORD' COMMENT 'KEYWORD/REGEX/LLM',
  `pattern` VARCHAR(500) NOT NULL,
  `score_delta` INT DEFAULT -10,
  `severity` VARCHAR(16) DEFAULT 'WARN',
  `enabled` CHAR(1) DEFAULT '0',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csqr_ws_enabled` (`workspace_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服质检规则';

CREATE TABLE IF NOT EXISTS `agent_cs_quality_check` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `message_id` BIGINT NULL,
  `ticket_id` BIGINT NULL,
  `workspace_id` BIGINT NULL,
  `score` INT DEFAULT 100,
  `hit_rules` VARCHAR(1000) NULL,
  `source` VARCHAR(16) DEFAULT 'AUTO',
  `review_status` VARCHAR(16) DEFAULT 'PENDING',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_csqc_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服质检结果';

-- ---------------- FAQ / 补录答案（审核 + 生效期） ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_knowledge_answer` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL,
  `knowledge_id` BIGINT NULL,
  `question` VARCHAR(500) NOT NULL,
  `question_hash` CHAR(64) NOT NULL,
  `answer` TEXT NOT NULL,
  `source` VARCHAR(32) DEFAULT 'manual',
  `source_ref_id` BIGINT NULL,
  `status` VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REJECTED/EXPIRED',
  `effective_from` DATETIME NULL,
  `effective_to` DATETIME NULL,
  `audit_by` BIGINT NULL,
  `audit_note` VARCHAR(500) NULL,
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cska_hash` (`workspace_id`, `question_hash`),
  KEY `idx_cska_status` (`status`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FAQ与补录答案';

-- ---------------- 客服日指标 ----------------
CREATE TABLE IF NOT EXISTS `agent_cs_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stat_date` DATE NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `channel_code` VARCHAR(64) NULL,
  `bot_sessions` INT DEFAULT 0,
  `handoff_count` INT DEFAULT 0,
  `first_response_p95_ms` BIGINT DEFAULT 0,
  `resolve_rate` DECIMAL(6,2) DEFAULT 0,
  `handoff_rate` DECIMAL(6,2) DEFAULT 0,
  `csat_avg` DECIMAL(4,2) DEFAULT 0,
  `csat_response_rate` DECIMAL(6,2) DEFAULT 0,
  `sla_pass_rate` DECIMAL(6,2) DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csmd` (`stat_date`, `workspace_id`, `channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服日指标';

-- ---------------- 存量表扩展（幂等） ----------------
CALL `cs_add_column`('agent_session', 'scene', "`scene` VARCHAR(16) DEFAULT 'console' COMMENT 'console/cs'");
CALL `cs_add_column`('agent_session', 'visitor_id', '`visitor_id` BIGINT NULL COMMENT "客服访客ID"');
CALL `cs_add_column`('agent_session', 'channel_code', '`channel_code` VARCHAR(64) NULL COMMENT "客服渠道编码"');
CALL `cs_add_column`('agent_session', 'cs_ticket_id', '`cs_ticket_id` BIGINT NULL COMMENT "当前工单ID"');
CALL `cs_add_index`('agent_session', 'idx_as_scene_visitor', '`idx_as_scene_visitor` (`scene`, `visitor_id`)');

CALL `cs_add_column`('agent_message', 'client_msg_id', '`client_msg_id` VARCHAR(64) NULL COMMENT "客户端幂等键"');
CALL `cs_add_column`('agent_message', 'sender_type', "`sender_type` VARCHAR(16) DEFAULT 'user' COMMENT 'user/assistant/seat/system'");
CALL `cs_add_column`('agent_message', 'ticket_id', '`ticket_id` BIGINT NULL COMMENT "客服工单ID"');
CALL `cs_add_index`('agent_message', 'uk_am_client', '`uk_am_client` (`session_id`, `client_msg_id`)');

CALL `cs_add_column`('agent_run', 'channel_code', '`channel_code` VARCHAR(64) NULL COMMENT "客服渠道编码"');
CALL `cs_add_column`('agent_run', 'visitor_id', '`visitor_id` BIGINT NULL COMMENT "客服访客ID"');
CALL `cs_add_column`('agent_run', 'cs_ticket_id', '`cs_ticket_id` BIGINT NULL COMMENT "客服工单ID"');

CALL `cs_add_column`('agent_token_usage', 'channel_code', '`channel_code` VARCHAR(64) NULL COMMENT "客服渠道编码"');

DROP PROCEDURE IF EXISTS `cs_add_column`;
DROP PROCEDURE IF EXISTS `cs_add_index`;
