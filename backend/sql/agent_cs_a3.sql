-- =====================================================================
-- 智能客服 A3(意图体系)建表与种子
-- 目标库: forge-vue
-- 幂等: 建表 IF NOT EXISTS；种子按 (workspace_id, intent_code) 判断；菜单按 menu_id
-- 说明: 意图是坐席辅助的第一层判断——先知道客户在问什么，再决定推什么话术。
--       workspace_id 为 NULL 的行是平台级意图，对所有空间生效（与质检规则同口径）。
-- =====================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_cs_intent` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT NULL COMMENT '归属空间(NULL=平台级,对所有空间生效)',
  `intent_code` VARCHAR(64) NOT NULL COMMENT '意图编码(落推荐记录,运营聚合键)',
  `intent_name` VARCHAR(64) NOT NULL COMMENT '意图名称(坐席卡片展示)',
  `keywords` TEXT NULL COMMENT '关键词(逗号分隔,任一命中即打标)',
  `rule_type` VARCHAR(16) DEFAULT 'KEYWORD' COMMENT 'KEYWORD / REGEX',
  `kb_ids` VARCHAR(255) NULL COMMENT '关联知识库ID(逗号分隔),命中后并入检索范围',
  `priority` INT DEFAULT 100 COMMENT '匹配优先级(小者优先)',
  `enabled` CHAR(1) DEFAULT '1' COMMENT '启用 1/0',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csintent_ws_code` (`workspace_id`, `intent_code`),
  KEY `idx_csintent_enabled` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='坐席辅助意图标签';

-- 平台级种子意图(priority 越小越先匹配):覆盖客服最高频的五类进线诉求
INSERT INTO agent_cs_intent
 (workspace_id, intent_code, intent_name, keywords, rule_type, kb_ids, priority, enabled, create_time, update_time)
SELECT NULL, 'RETURN_REFUND', '退换退款', '退货,退款,换货,退钱,不想要了', 'KEYWORD', NULL, 10, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_cs_intent WHERE workspace_id IS NULL AND intent_code = 'RETURN_REFUND');

INSERT INTO agent_cs_intent
 (workspace_id, intent_code, intent_name, keywords, rule_type, kb_ids, priority, enabled, create_time, update_time)
SELECT NULL, 'ORDER_STATUS', '订单查询', '订单,发货,物流,快递,到哪了,什么时候到', 'KEYWORD', NULL, 20, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_cs_intent WHERE workspace_id IS NULL AND intent_code = 'ORDER_STATUS');

INSERT INTO agent_cs_intent
 (workspace_id, intent_code, intent_name, keywords, rule_type, kb_ids, priority, enabled, create_time, update_time)
SELECT NULL, 'INVOICE', '发票开具', '发票,开票,税号,抬头', 'KEYWORD', NULL, 30, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_cs_intent WHERE workspace_id IS NULL AND intent_code = 'INVOICE');

INSERT INTO agent_cs_intent
 (workspace_id, intent_code, intent_name, keywords, rule_type, kb_ids, priority, enabled, create_time, update_time)
SELECT NULL, 'COMPLAINT', '投诉反馈', '投诉,差评,太差,不满意,举报,态度', 'KEYWORD', NULL, 15, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_cs_intent WHERE workspace_id IS NULL AND intent_code = 'COMPLAINT');

INSERT INTO agent_cs_intent
 (workspace_id, intent_code, intent_name, keywords, rule_type, kb_ids, priority, enabled, create_time, update_time)
SELECT NULL, 'HUMAN_HANDOFF', '转人工', '转人工,人工客服,找人工,转客服', 'KEYWORD', NULL, 5, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_cs_intent WHERE workspace_id IS NULL AND intent_code = 'HUMAN_HANDOFF');

-- 运营菜单:意图管理(挂在「智能客服」目录下,与其余运营页同级)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040010, '意图管理', 1761400000000040001, 6, 'intents',
       'customerservice/Intents', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:intent:list', 'tree', null, null, 1, now(), '意图标签与规则打标'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040010);
