-- =====================================================================
-- 语音与呼叫中心模块（V2 业务打通）增量
-- 目标库: forge-vue
-- 幂等: 建表 CREATE TABLE IF NOT EXISTS；渠道与菜单按唯一键 / menu_id 判断，可重复执行
-- 说明: 语音渠道在 agent_cs_channel 幂等种一行 voice_inbound，文本域 Service
--       (建会话 / 转人工 / CSAT / 质检 / 指标) 原样复用；不给 agent_cs_* 加列
-- =====================================================================
SET NAMES utf8mb4;

-- ---------------- 通话日指标(callcenter 自有,不扩 agent_cs_metric_daily) ----------------
CREATE TABLE IF NOT EXISTS `agent_cc_metric_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `stat_date` DATE NOT NULL,
  `workspace_id` BIGINT NOT NULL,
  `call_count` INT DEFAULT 0 COMMENT '通话总数',
  `answered_count` INT DEFAULT 0 COMMENT '接通数',
  `answer_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '接通率 %',
  `avg_duration_sec` INT DEFAULT 0 COMMENT '平均通话时长(秒)',
  `bot_resolve_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '机器人自助解决率 %(无工单占比)',
  `recording_coverage` DECIMAL(5,2) DEFAULT 0 COMMENT '录音覆盖率 %',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ccmd_day` (`stat_date`, `workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话日指标';

-- ---------------- 语音渠道(文本域复用入口):与 agent_cc_channel_config 同码 ----------------
INSERT INTO agent_cs_channel
 (channel_code, channel_type, workspace_id, agent_id, owner_user_id, knowledge_ids,
  fallback_reply, handoff_policy, cors_allowlist, rate_limit, status, create_time)
VALUES ('CC_MOCK','voice_inbound',1,1,1,'','语音服务暂时不可用,请稍后再拨','manual',
        'example.com',60,'0',NOW())
ON DUPLICATE KEY UPDATE status='0', channel_type='voice_inbound',
  fallback_reply=VALUES(fallback_reply), handoff_policy=VALUES(handoff_policy);

-- ---------------- 通话记录菜单(40009) ----------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
SELECT 1761400000000040009, '通话记录', 1761400000000040001, 7, 'calls',
       'customerservice/Calls', null, 'N', 'Y', 'C', '0', '0',
       'customerservice:call:list', 'phone', null, null, 1, now(), '语音通话列表 / 转写 / 录音回听'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000040009);
