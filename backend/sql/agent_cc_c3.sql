-- =====================================================================
-- 呼叫中心 C3(IVR 节点引擎)增量
-- 目标库: forge-vue
-- 幂等: 全部 CREATE TABLE IF NOT EXISTS，可重复执行
-- 说明: 流程定义 JSON 存 flow longtext(对齐 SmartCall ivr_call_route 的建模),
--       节点鉴别器 type 对齐 forge-callcenter engine 包 IvrNode 子类型
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- IVR 流程定义 ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_ivr_flow` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `route_code` VARCHAR(64) NOT NULL COMMENT '流程编码,渠道按此引用',
  `route_name` VARCHAR(128) NOT NULL COMMENT '流程名称',
  `workspace_id` BIGINT NOT NULL DEFAULT 1 COMMENT '归属空间',
  `process` TINYINT NOT NULL DEFAULT 1 COMMENT '1=呼入 2=呼出(对齐 SmartCall ivr_call_route)',
  `flow` LONGTEXT NOT NULL COMMENT '节点 JSON 数组,首节点 type=start;节点鉴别器 type',
  `status` CHAR(1) NOT NULL DEFAULT '0' COMMENT '0=启用 1=停用',
  `del_flag` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ivrflow_route` (`workspace_id`, `route_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IVR 流程定义(C3 节点引擎)';

-- 开发演示流程种子(幂等):say → answer → intention(退货/查单/other) → agent → transfer
INSERT INTO `agent_cc_ivr_flow` (`route_code`, `route_name`, `workspace_id`, `process`, `flow`, `status`, `del_flag`, `create_time`)
SELECT 'demo_inbound', '演示呼入流程', 1, 1,
'{[
  {"id":"s","type":"start","nextId":"n1"},
  {"id":"n1","type":"say","text":"您好,欢迎致电,请问有什么可以帮您","nextId":"n2"},
  {"id":"n2","type":"answer","prompt":"","timeoutMs":8000,"nextId":"n3","silenceNextId":"n1"},
  {"id":"n3","type":"intention","branches":{"refund":"n4","query_order":"n4"},"otherNextId":"n6"},
  {"id":"n4","type":"agent","noAnswerNextId":"n6","nextId":"n6b"},
  {"id":"n6","type":"say","text":"暂未找到相关答案,已为您转接人工","nextId":"n5"},
  {"id":"n6b","type":"say","text":"好的,关于退货问题,为您说明如下","nextId":"n5"},
  {"id":"n5","type":"transfer","queue":"after_sales"}
]}' AS flow,
'0', 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `agent_cc_ivr_flow` WHERE workspace_id = 1 AND route_code = 'demo_inbound' AND del_flag = 0);
