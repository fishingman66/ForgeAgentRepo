-- =====================================================================
-- ForgeAgent 业务种子(Docker 首次启动第 4 步)
-- 目标库: forge-vue
-- 说明: 01-schema 只建表结构,02-seed 只放框架级数据(sys_menu/sys_role/sys_dict),
--       03-demo 只放演示账号与空间;平台运行所必需的业务默认数据(模型供应商、
--       内置工具、客服意图/敏感词/质检规则、呼叫中心渠道/打断词/IVR/路由)
--       原先散落在 backend/sql/*.sql 增量脚本里,Docker 部署时不会执行。
--       本文件把它们收口成一份,保证 docker compose 起库后系统即可用。
--
-- 幂等: 每条 INSERT 均带 WHERE NOT EXISTS(或 ON DUPLICATE KEY),可重复执行。
--
-- 归属空间: 不写死 id——开发库与新库的空间 id 不同(前者是自建测试空间,
-- 后者由 03-demo 建演示空间)。这里动态取当前库的默认空间/Agent/用户,
-- 保证种子始终挂在真实存在的对象下,不会变成孤儿数据。
-- 若库里还没有空间/Agent(channel 依赖),对应种子自动跳过,不影响其余数据。
-- =====================================================================
SET NAMES utf8mb4;

-- 当前库的默认空间 / 默认 Agent / 默认用户(按 id 升序取第一条)
SET @default_ws := (SELECT `id` FROM `agent_workspace` ORDER BY `id` LIMIT 1);
SET @default_agent := (SELECT `id` FROM `agent_info` ORDER BY `id` LIMIT 1);
SET @default_user := (SELECT `user_id` FROM `sys_user` ORDER BY `user_id` LIMIT 1);

-- ----------------------------
-- 1. 模型供应商(平台级)
-- ----------------------------
INSERT INTO `agent_provider` (`provider_code`, `provider_name`, `status`, `remark`, `create_by`, `create_time`)
SELECT 'openai', 'OpenAI', '0', 'GPT 系列模型', 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_provider` WHERE provider_code = 'openai');

INSERT INTO `agent_provider` (`provider_code`, `provider_name`, `status`, `remark`, `create_by`, `create_time`)
SELECT 'anthropic', 'Anthropic', '0', 'Claude 系列模型', 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_provider` WHERE provider_code = 'anthropic');

INSERT INTO `agent_provider` (`provider_code`, `provider_name`, `status`, `remark`, `create_by`, `create_time`)
SELECT 'dashscope', '阿里云百炼', '0', '通义千问系列模型', 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_provider` WHERE provider_code = 'dashscope');

INSERT INTO `agent_provider` (`provider_code`, `provider_name`, `status`, `remark`, `create_by`, `create_time`)
SELECT 'ollama', 'Ollama', '0', '本地开源模型', 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_provider` WHERE provider_code = 'ollama');

INSERT INTO `agent_provider` (`provider_code`, `provider_name`, `status`, `remark`, `create_by`, `create_time`)
SELECT 'custom', '自定义', '0', 'OpenAI 兼容接口', 1, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_provider` WHERE provider_code = 'custom');

-- ----------------------------
-- 2. 内置工具:Python 沙箱
-- ----------------------------
INSERT INTO `agent_tool` (`name`, `description`, `tool_type`, `status`, `approval_mode`, `approval_timeout`, `remark`, `create_time`, `update_time`)
SELECT 'python_sandbox',
       '在隔离 Docker 容器中执行 Python 代码(无网络、内存/CPU受限、超时保护、输出截断),参数 code 为要执行的 Python 源码',
       'sandbox', '0', 'auto', 60,
       '内建沙箱工具:镜像 python:3.11-slim,无网络,默认内存128m/CPU0.5/超时30秒',
       NOW(), NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_tool` WHERE name = 'python_sandbox');

-- ----------------------------
-- 3. 客服意图(平台级,workspace_id IS NULL 对所有空间生效)
-- ----------------------------
INSERT INTO `agent_cs_intent`
 (`workspace_id`, `intent_code`, `intent_name`, `keywords`, `rule_type`, `kb_ids`, `priority`, `enabled`, `create_time`, `update_time`)
SELECT NULL, 'RETURN_REFUND', '退换退款', '退货,退款,换货,退钱,不想要了', 'KEYWORD', NULL, 10, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_intent` WHERE workspace_id IS NULL AND intent_code = 'RETURN_REFUND');

INSERT INTO `agent_cs_intent`
 (`workspace_id`, `intent_code`, `intent_name`, `keywords`, `rule_type`, `kb_ids`, `priority`, `enabled`, `create_time`, `update_time`)
SELECT NULL, 'ORDER_STATUS', '订单查询', '订单,发货,物流,快递,到哪了,什么时候到', 'KEYWORD', NULL, 20, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_intent` WHERE workspace_id IS NULL AND intent_code = 'ORDER_STATUS');

INSERT INTO `agent_cs_intent`
 (`workspace_id`, `intent_code`, `intent_name`, `keywords`, `rule_type`, `kb_ids`, `priority`, `enabled`, `create_time`, `update_time`)
SELECT NULL, 'INVOICE', '发票开具', '发票,开票,税号,抬头', 'KEYWORD', NULL, 30, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_intent` WHERE workspace_id IS NULL AND intent_code = 'INVOICE');

INSERT INTO `agent_cs_intent`
 (`workspace_id`, `intent_code`, `intent_name`, `keywords`, `rule_type`, `kb_ids`, `priority`, `enabled`, `create_time`, `update_time`)
SELECT NULL, 'COMPLAINT', '投诉反馈', '投诉,差评,太差,不满意,举报,态度', 'KEYWORD', NULL, 15, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_intent` WHERE workspace_id IS NULL AND intent_code = 'COMPLAINT');

INSERT INTO `agent_cs_intent`
 (`workspace_id`, `intent_code`, `intent_name`, `keywords`, `rule_type`, `kb_ids`, `priority`, `enabled`, `create_time`, `update_time`)
SELECT NULL, 'HUMAN_HANDOFF', '转人工', '转人工,人工客服,找人工,转客服', 'KEYWORD', NULL, 5, '1', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_intent` WHERE workspace_id IS NULL AND intent_code = 'HUMAN_HANDOFF');

-- ----------------------------
-- 4. 客服敏感词(平台级示例,生产按实际词库维护)
-- ----------------------------
INSERT INTO `agent_cs_sensitive_word` (`workspace_id`, `word`, `severity`, `remark`, `enabled`, `create_time`)
SELECT NULL, '竞品A', 'MASK', '示例:竞品名打码(生产按实际词库维护)', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_sensitive_word` WHERE workspace_id IS NULL AND word = '竞品A');

-- ----------------------------
-- 5. 质检规则(平台级)
-- ----------------------------
INSERT INTO `agent_cs_quality_rule` (`workspace_id`, `rule_type`, `pattern`, `score_delta`, `severity`, `enabled`, `create_time`)
SELECT NULL, 'KEYWORD', '不知道', -10, 'WARN', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_quality_rule` WHERE workspace_id IS NULL AND pattern = '不知道');

INSERT INTO `agent_cs_quality_rule` (`workspace_id`, `rule_type`, `pattern`, `score_delta`, `severity`, `enabled`, `create_time`)
SELECT NULL, 'KEYWORD', '不归我管', -20, 'HIGH', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_quality_rule` WHERE workspace_id IS NULL AND pattern = '不归我管');

INSERT INTO `agent_cs_quality_rule` (`workspace_id`, `rule_type`, `pattern`, `score_delta`, `severity`, `enabled`, `create_time`)
SELECT NULL, 'KEYWORD', '自己看', -15, 'WARN', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_quality_rule` WHERE workspace_id IS NULL AND pattern = '自己看');

INSERT INTO `agent_cs_quality_rule` (`workspace_id`, `rule_type`, `pattern`, `score_delta`, `severity`, `enabled`, `create_time`)
SELECT NULL, 'KEYWORD', '没办法', -10, 'WARN', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_quality_rule` WHERE workspace_id IS NULL AND pattern = '没办法');

INSERT INTO `agent_cs_quality_rule` (`workspace_id`, `rule_type`, `pattern`, `score_delta`, `severity`, `enabled`, `create_time`)
SELECT NULL, 'KEYWORD', '保证', -5, 'LOW', '1', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `agent_cs_quality_rule` WHERE workspace_id IS NULL AND pattern = '保证');

-- ----------------------------
-- 6. 语音渠道(与 agent_cc_channel_config 同码)
-- ----------------------------
INSERT INTO `agent_cs_channel`
 (`channel_code`, `channel_type`, `workspace_id`, `agent_id`, `owner_user_id`, `knowledge_ids`,
  `fallback_reply`, `handoff_policy`, `cors_allowlist`, `rate_limit`, `status`, `create_time`)
SELECT 'CC_MOCK', 'voice_inbound', @default_ws, @default_agent, @default_user, '',
       '语音服务暂时不可用,请稍后再拨', 'manual', 'example.com', 60, '0', NOW() FROM DUAL
WHERE @default_ws IS NOT NULL AND @default_agent IS NOT NULL AND @default_user IS NOT NULL
ON DUPLICATE KEY UPDATE status = '0', channel_type = 'voice_inbound',
  fallback_reply = VALUES(fallback_reply), handoff_policy = VALUES(handoff_policy);

-- ----------------------------
-- 7. 语音打断豁免词(workspace_id=0 表示全局;与 CallcenterProperties.noInterruptWords 一致)
-- ----------------------------
INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT '嗯', 'exact', '1', 0, 0, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '嗯' AND match_mode = 'exact' AND del_flag = 0);

INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT '哦', 'exact', '1', 0, 0, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '哦' AND match_mode = 'exact' AND del_flag = 0);

INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT '好的', 'exact', '1', 0, 0, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '好的' AND match_mode = 'exact' AND del_flag = 0);

INSERT INTO `agent_cc_voice_no_interrupt` (`word`, `match_mode`, `enabled`, `workspace_id`, `del_flag`, `create_time`)
SELECT '麻烦', 'fuzzy', '0', 0, 0, NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_voice_no_interrupt` WHERE workspace_id = 0 AND word = '麻烦' AND match_mode = 'fuzzy' AND del_flag = 0);

-- ----------------------------
-- 8. 演示 IVR 流程:say → answer → intention(退货/查单/other) → agent → transfer
-- ----------------------------
INSERT INTO `agent_cc_ivr_flow` (`route_code`, `route_name`, `workspace_id`, `process`, `flow`, `status`, `del_flag`, `create_time`)
SELECT 'demo_inbound', '演示呼入流程', @default_ws, 1,
'[
  {"id":"s","type":"start","nextId":"n1"},
  {"id":"n1","type":"say","text":"您好,欢迎致电,请问有什么可以帮您","nextId":"n2"},
  {"id":"n2","type":"answer","prompt":"","timeoutMs":8000,"nextId":"n3","silenceNextId":"n1"},
  {"id":"n3","type":"intention","branches":{"refund":"n4","query_order":"n4"},"otherNextId":"n6"},
  {"id":"n4","type":"agent","noAnswerNextId":"n6","nextId":"n6b"},
  {"id":"n6","type":"say","text":"暂未找到相关答案,已为您转接人工","nextId":"n5"},
  {"id":"n6b","type":"say","text":"好的,关于退货问题,为您说明如下","nextId":"n5"},
  {"id":"n5","type":"transfer","queue":"after_sales"}
]' AS flow,
'0', 0, NOW()
WHERE @default_ws IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `agent_cc_ivr_flow` WHERE workspace_id = @default_ws AND route_code = 'demo_inbound' AND del_flag = 0);

-- ----------------------------
-- 9. 演示分流规则(人类诉求优先,再判业务意图)
-- ----------------------------
INSERT INTO `agent_cc_route_rule`
  (`workspace_id`, `rule_code`, `priority`, `match_type`, `pattern`, `intent_code`, `action`, `action_arg`, `phrase_whitelist`, `max_match_length`, `enabled`, `del_flag`)
SELECT @default_ws, 'human_first', 5, 'REGEX', '转人工|找人工|人工客服|叫个人', 'human', 'TRANSFER', 'after_sales', NULL, 16, '0', 0
WHERE @default_ws IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id = @default_ws AND rule_code = 'human_first' AND del_flag = 0);

INSERT INTO `agent_cc_route_rule`
  (`workspace_id`, `rule_code`, `priority`, `match_type`, `pattern`, `intent_code`, `action`, `action_arg`, `phrase_whitelist`, `max_match_length`, `enabled`, `del_flag`)
SELECT @default_ws, 'refund', 20, 'REGEX', '退', 'refund', 'TRANSFER', 'after_sales', '退货,退款,退钱', 20, '0', 0
WHERE @default_ws IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id = @default_ws AND rule_code = 'refund' AND del_flag = 0);

INSERT INTO `agent_cc_route_rule`
  (`workspace_id`, `rule_code`, `priority`, `match_type`, `pattern`, `intent_code`, `action`, `action_arg`, `phrase_whitelist`, `max_match_length`, `enabled`, `del_flag`)
SELECT @default_ws, 'order_query', 30, 'REGEX', '(?:查|查询|看一下|看下).{0,6}(?:订单|单号|物流|快递)', 'query_order', 'ANSWER', 'order_status', NULL, 20, '0', 0
WHERE @default_ws IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id = @default_ws AND rule_code = 'order_query' AND del_flag = 0);

INSERT INTO `agent_cc_route_rule`
  (`workspace_id`, `rule_code`, `priority`, `match_type`, `pattern`, `intent_code`, `action`, `action_arg`, `phrase_whitelist`, `max_match_length`, `enabled`, `del_flag`)
SELECT @default_ws, 'complaint_llm', 90, 'LLM_ONLY', NULL, 'complaint', 'MARK', NULL, NULL, 40, '0', 0
WHERE @default_ws IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `agent_cc_route_rule` WHERE workspace_id = @default_ws AND rule_code = 'complaint_llm' AND del_flag = 0);
