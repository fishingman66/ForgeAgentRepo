-- =====================================================================
-- 呼叫中心 C5(双引擎分流规则引擎)增量
-- 目标库: forge-vue
-- 幂等: CREATE TABLE IF NOT EXISTS + 逐条 NOT EXISTS 种子
-- 说明: 对齐方案 docs/07 §三 P0-3。规则按 priority 升序(小者优先)命中即止。
--       与文档列的差异(已回写 docs/07):文档写的 no_interrupt_words 与 C2 的
--       不打断词表(agent_cc_voice_no_interrupt)语义重叠,本表改为 phrase_whitelist,
--       含义是「词组白名单:非空时必须全部出现才命中」,用于压制正则过匹配。
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 分流规则 ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_route_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NOT NULL DEFAULT 1 COMMENT '归属空间',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '规则编码,日志与排障用',
  `priority` INT NOT NULL DEFAULT 100 COMMENT '优先级,数值越小越先匹配',
  `match_type` VARCHAR(16) NOT NULL DEFAULT 'REGEX' COMMENT 'REGEX / LLM_ONLY',
  `pattern` VARCHAR(512) NULL COMMENT 'REGEX 正则;LLM_ONLY 留空',
  `intent_code` VARCHAR(64) NOT NULL COMMENT '命中后产出的意图码',
  `action` VARCHAR(32) NOT NULL DEFAULT 'MARK' COMMENT 'TRANSFER(转队列)/ANSWER(直接应答)/MARK(仅记录)',
  `action_arg` VARCHAR(128) NULL COMMENT 'TRANSFER=队列名;ANSWER=应答键',
  `phrase_whitelist` VARCHAR(512) NULL COMMENT '词组白名单,英文逗号分隔;非空时文本须至少出现其一',
  `max_match_length` INT NOT NULL DEFAULT 40 COMMENT '量词防护:命中片段上限(字符),超出视为过匹配',
  `enabled` CHAR(1) NOT NULL DEFAULT '0' COMMENT '0=启用 1=停用',
  `del_flag` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccrr_code` (`workspace_id`, `rule_code`),
  KEY `idx_ccrr_prio` (`workspace_id`, `enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分流规则(双引擎:正则优先,LLM 兜底)';

-- ---------------- 开发演示规则(幂等) ----------------
-- 人类诉求优先于一切:先判转人工,再判业务意图
INSERT INTO `agent_cc_route_rule`
  (`workspace_id`,`rule_code`,`priority`,`match_type`,`pattern`,`intent_code`,`action`,`action_arg`,`phrase_whitelist`,`max_match_length`,`enabled`,`del_flag`)
SELECT 1,'human_first',5,'REGEX','转人工|找人工|人工客服|叫个人','human','TRANSFER','after_sales',NULL,16,'0',0
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id=1 AND rule_code='human_first' AND del_flag=0);

-- 退货退款:pattern 故意放宽到「退」,靠白名单挡掉「退休/退订」一类误匹配
INSERT INTO `agent_cc_route_rule`
  (`workspace_id`,`rule_code`,`priority`,`match_type`,`pattern`,`intent_code`,`action`,`action_arg`,`phrase_whitelist`,`max_match_length`,`enabled`,`del_flag`)
SELECT 1,'refund',20,'REGEX','退','refund','TRANSFER','after_sales','退货,退款,退钱',20,'0',0
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id=1 AND rule_code='refund' AND del_flag=0);

-- 查单:量词用有限界 .{0,6},不写 .*
INSERT INTO `agent_cc_route_rule`
  (`workspace_id`,`rule_code`,`priority`,`match_type`,`pattern`,`intent_code`,`action`,`action_arg`,`phrase_whitelist`,`max_match_length`,`enabled`,`del_flag`)
SELECT 1,'order_query',30,'REGEX','(?:查|查询|看一下|看下).{0,6}(?:订单|单号|物流|快递)','query_order','ANSWER','order_status',NULL,20,'0',0
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id=1 AND rule_code='order_query' AND del_flag=0);

-- 投诉:交给 LLM 判定(正则无法区分抱怨与陈述),未装 LLM 时降级到正则库
INSERT INTO `agent_cc_route_rule`
  (`workspace_id`,`rule_code`,`priority`,`match_type`,`pattern`,`intent_code`,`action`,`action_arg`,`phrase_whitelist`,`max_match_length`,`enabled`,`del_flag`)
SELECT 1,'complaint_llm',90,'LLM_ONLY',NULL,'complaint','MARK',NULL,NULL,40,'0',0
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id=1 AND rule_code='complaint_llm' AND del_flag=0);
