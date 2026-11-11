-- =====================================================================
-- 语音与呼叫中心模块（V1 适配层）建表
-- 目标库: forge-vue
-- 幂等: 全部 CREATE TABLE IF NOT EXISTS，可重复执行
-- 归属: forge-callcenter 模块；表前缀 agent_cc_（C 形态改进：不给 agent_cs_* 加列）
-- 说明: 表存在与否不影响主应用启动；agent.callcenter.enabled=false 时无语音端点
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 通话主记录 ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_call` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` VARCHAR(128) NOT NULL COMMENT 'AICC 呼叫标识,幂等键',
  `media_provider` VARCHAR(32) NOT NULL DEFAULT 'AICC_MOCK' COMMENT '供应商标识',
  `channel_code` VARCHAR(64) NOT NULL COMMENT '语音渠道编码,关联 agent_cc_channel_config',
  `workspace_id` BIGINT NOT NULL COMMENT '归属空间',
  `agent_id` BIGINT NOT NULL COMMENT '客服机器人 AgentID',
  `conversation_id` BIGINT NULL COMMENT '映射客服会话(V2 由 CallBusinessBinder 写入)',
  `ticket_id` BIGINT NULL COMMENT '映射工单(V2)',
  `direction` VARCHAR(16) NOT NULL DEFAULT 'inbound',
  `caller_number` VARCHAR(64) NULL COMMENT 'PII 脱敏后存储',
  `callee_number` VARCHAR(64) NULL,
  `ani` VARCHAR(64) NULL,
  `dnis` VARCHAR(64) NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'RINGING' COMMENT 'RINGING/ANSWERED/BOT_SERVING/WAITING_AGENT/AGENT_SERVING/HOLD/WRAP_UP/ENDED',
  `bot_handled` CHAR(1) DEFAULT '0',
  `work_no` VARCHAR(64) NULL COMMENT 'AICC 坐席工号',
  `ring_at` DATETIME NULL,
  `answer_at` DATETIME NULL,
  `hangup_at` DATETIME NULL,
  `duration_sec` INT DEFAULT 0,
  `bill_sec` INT DEFAULT 0,
  `hangup_cause` VARCHAR(64) NULL,
  `hangup_by` VARCHAR(16) NULL,
  `sip_code` INT NULL,
  `recording_oss_key` VARCHAR(500) NULL COMMENT '本地 OSS 对象键,不存云侧 URL',
  `recording_id` VARCHAR(128) NULL,
  `recording_ready_at` DATETIME NULL,
  `recording_retain_until` DATETIME NULL,
  `ivr_path` VARCHAR(255) NULL,
  `dtmf_digits` VARCHAR(128) NULL,
  `transfer_from` VARCHAR(64) NULL COMMENT 'AICC 侧为技能组/坐席字符串',
  `transfer_to` VARCHAR(64) NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccall_provider` (`media_provider`, `call_id`),
  KEY `idx_cccall_conv` (`conversation_id`),
  KEY `idx_cccall_status` (`status`, `ring_at`),
  KEY `idx_cccall_retain` (`recording_retain_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服通话主记录';

-- ---------------- 通话事件轨迹(只追加) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_call_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `event_seq` BIGINT NOT NULL COMMENT '供应商 eventGenTime(ms),乱序判定',
  `event_type` VARCHAR(32) NOT NULL COMMENT '归一化事件名',
  `from_status` VARCHAR(24) NULL,
  `to_status` VARCHAR(24) NULL,
  `actor_type` VARCHAR(16) NOT NULL DEFAULT 'PROVIDER',
  `payload` VARCHAR(2000) NULL COMMENT '厂商原始事件摘要,异常时存死信原因',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccce_dedup` (`call_id`, `event_seq`, `event_type`),
  KEY `idx_ccce_call` (`call_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话事件轨迹';

-- ---------------- 实时转写分片(弹屏/质检/摘要数据源) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_call_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `call_id` BIGINT NOT NULL COMMENT 'agent_cc_call.id',
  `speaker` VARCHAR(16) NOT NULL COMMENT 'caller/bot/seat',
  `text` TEXT NULL COMMENT 'PII 脱敏后文本',
  `start_ms` BIGINT NULL,
  `end_ms` BIGINT NULL,
  `asr_confidence` DECIMAL(5,2) NULL,
  `is_final` CHAR(1) DEFAULT '0',
  `create_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cccs_call` (`call_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话转写分片';

-- ---------------- 电话坐席就绪态(按 AICC 工号) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_call_seat_state` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `seat_id` BIGINT NULL COMMENT 'V2 绑定 agent_cs_seat 后回填',
  `user_id` BIGINT NULL,
  `workspace_id` BIGINT NULL,
  `work_no` VARCHAR(64) NOT NULL COMMENT 'AICC 坐席工号,业务唯一键',
  `extension` VARCHAR(32) NULL,
  `ready_state` VARCHAR(16) DEFAULT 'OFFLINE' COMMENT 'OFFLINE/READY/PAUSE/TALKING/WRAP_UP',
  `pause_reason` VARCHAR(64) NULL,
  `login_at` DATETIME NULL,
  `logout_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccss_workno` (`work_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电话坐席状态';

-- ---------------- 语音渠道配置(callcenter 自有,不扩 agent_cs_channel) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_channel_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(64) NOT NULL COMMENT '语音渠道端点编码,回调 URL /chat-voice/{provider}/{code}',
  `media_provider` VARCHAR(32) NOT NULL DEFAULT 'AICC_MOCK',
  `service_number` VARCHAR(64) NULL COMMENT '服务号码,V0~V2 允许为空,由回调事件带入',
  `workspace_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL COMMENT '客服机器人 AgentID',
  `callback_secret` VARCHAR(200) NULL COMMENT '渠道级共享密钥,CsSecretCipher 加密存;空则用全局 agent.callcenter.callback-secret',
  `voice_config_json` TEXT NULL COMMENT '供应商参数/并发上限/转人工阈值/录音开关',
  `rate_limit` INT DEFAULT 60 COMMENT '每分钟回调上限',
  `status` CHAR(1) DEFAULT '0' COMMENT '0启用 1停用',
  `del_flag` BIGINT DEFAULT 0,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cccc_code` (`channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='语音渠道配置';
