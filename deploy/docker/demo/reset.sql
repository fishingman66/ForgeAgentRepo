-- ============================================================================
-- 演示环境数据清理（配合 03-demo.sql 的每日重置）
--
-- 原则：保留预置「配置」，只清 demo 账号与演示空间产生的「运行时数据」。
--   保留：Agent / 空间与成员 / 数据源 / 模型 / 工具 / MCP / 知识库与文档 /
--         触发器配置 / 客服渠道 / 质检规则 / 敏感词 / 意图 / 知识答案 /
--         意图路由 / 语音渠道配置 / Prompt 模板 / Agent 版本快照 / 评测用例
--   清理：会话与消息（含归档）/ 运行轨迹 / 审批 / 工作流运行 / 记忆与图谱 /
--         客服会话·工单·质检·指标·缓存·缺口·动作幂等 / 语音通话 / 用量与审计 / 日志 /
--         市场安装（连同安装产生的 Agent 副本）/ 评测与门禁的执行记录 /
--         客服回流评测用例（source=cs_badcase/cs_sampled，手工用例保留）
--
-- 演示标识：用户 1900000000000000001 / 空间 1900000000000000101 /
--          预置 Agent 1900000000000001001~1004
-- 执行顺序：先子表后主表（回执→会话、事件→工单、步骤→运行），避免残留孤儿行。
-- ============================================================================
SET @demo_user := 1900000000000000001;
SET @demo_ws   := 1900000000000000101;

-- ---------------------------------------------------------------------------
-- 1. 智能客服：消息回执 / 建议 / 槽位 / 会话附属 → 工单附属 → 工单 → 会话 → 访客
-- ---------------------------------------------------------------------------
DELETE FROM agent_cs_message_receipt
 WHERE session_id IN (SELECT agent_session_id FROM agent_cs_conversation WHERE workspace_id = @demo_ws);

DELETE FROM agent_cs_seat_suggestion
 WHERE conversation_id IN (SELECT id FROM agent_cs_conversation WHERE workspace_id = @demo_ws);

DELETE FROM agent_cs_slot_progress
 WHERE conversation_id IN (SELECT id FROM agent_cs_conversation WHERE workspace_id = @demo_ws);

DELETE FROM agent_cs_ticket_event
 WHERE ticket_id IN (SELECT id FROM agent_cs_ticket WHERE workspace_id = @demo_ws);

DELETE FROM agent_cs_queue
 WHERE ticket_id IN (SELECT id FROM agent_cs_ticket WHERE workspace_id = @demo_ws);

DELETE FROM agent_cs_quality_check
 WHERE ticket_id IN (SELECT id FROM agent_cs_ticket WHERE workspace_id = @demo_ws);

DELETE FROM agent_cs_csat          WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_handoff_summary WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_dead_letter   WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_badcase       WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_knowledge_gap WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_semantic_cache WHERE workspace_id = @demo_ws;
-- C3 工具幂等预留表：不随会话级联删除，重置时按空间清，否则 uk(conversation,action_key) 越积越多
DELETE FROM agent_cs_action_log       WHERE workspace_id = @demo_ws;

DELETE FROM agent_cs_metric_daily        WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_cost_daily          WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_assist_metric_daily WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_kg_expand_metric    WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_audit_log           WHERE workspace_id = @demo_ws;

DELETE FROM agent_cs_ticket       WHERE workspace_id = @demo_ws;
DELETE FROM agent_cs_conversation WHERE workspace_id = @demo_ws;

DELETE FROM agent_cs_visitor
 WHERE channel_code IN (SELECT channel_code FROM agent_cs_channel WHERE workspace_id = @demo_ws);

-- 坐席档案属配置（坐席工作台需要），只复位运行时状态，不删行
UPDATE agent_cs_seat SET current_load = 0, online = '0' WHERE workspace_id = @demo_ws;

-- ---------------------------------------------------------------------------
-- 2. 语音呼叫：转写 / 事件 → 通话主记录；坐席就绪态与日指标
-- ---------------------------------------------------------------------------
DELETE FROM agent_cc_call_segment
 WHERE call_id IN (SELECT id FROM agent_cc_call WHERE workspace_id = @demo_ws);

DELETE FROM agent_cc_call_event
 WHERE call_id IN (SELECT id FROM agent_cc_call WHERE workspace_id = @demo_ws);

DELETE FROM agent_cc_call_seat_state WHERE workspace_id = @demo_ws;
-- C8:画像与呼损单先于通话主记录清理(都按 call_id 关联)
DELETE FROM agent_cc_call_profile
 WHERE call_id IN (SELECT id FROM agent_cc_call WHERE workspace_id = @demo_ws);
DELETE FROM agent_cc_callback_task
 WHERE call_id IN (SELECT id FROM agent_cc_call WHERE workspace_id = @demo_ws);
DELETE FROM agent_cc_call_insight
 WHERE call_id IN (SELECT id FROM agent_cc_call WHERE workspace_id = @demo_ws);
DELETE FROM agent_cc_metric_daily    WHERE workspace_id = @demo_ws;
DELETE FROM agent_cc_call            WHERE workspace_id = @demo_ws;

-- ---------------------------------------------------------------------------
-- 3. 对话主链：运行步骤 → 运行主记录、消息 → 会话、附件、审批、工作流运行
-- ---------------------------------------------------------------------------
DELETE FROM agent_run_step
 WHERE run_id IN (SELECT run_id FROM agent_run WHERE user_id = @demo_user OR workspace_id = @demo_ws);

DELETE FROM agent_run
 WHERE user_id = @demo_user OR workspace_id = @demo_ws;

DELETE FROM agent_message
 WHERE session_id IN (SELECT id FROM agent_session WHERE create_by = @demo_user);

DELETE FROM agent_message_archive
 WHERE owner_user_id = @demo_user
    OR session_id IN (SELECT id FROM agent_session WHERE create_by = @demo_user);

-- agent_chat_attachment 无 user_id 列（原脚本按 user_id 删除会直接报 1054 并中断整个重置）
DELETE FROM agent_chat_attachment
 WHERE create_by = @demo_user
    OR session_id IN (SELECT id FROM agent_session WHERE create_by = @demo_user);

DELETE FROM agent_session          WHERE create_by = @demo_user;
DELETE FROM agent_approval         WHERE create_by = @demo_user;
DELETE FROM agent_workflow_run     WHERE create_by = @demo_user;

-- ---------------------------------------------------------------------------
-- 4. 长期记忆与知识图谱（L3 语义向量随 agent_memory 删除；共享级 user_id=0 不动）
-- ---------------------------------------------------------------------------
DELETE FROM agent_memory         WHERE user_id = @demo_user;
DELETE FROM agent_memory_archive WHERE user_id = @demo_user;

DELETE FROM kg_edge
 WHERE user_id = @demo_user
    OR (scope_type = 'workspace' AND scope_id = @demo_ws);

DELETE FROM kg_node
 WHERE user_id = @demo_user
    OR (scope_type = 'workspace' AND scope_id = @demo_ws);

-- ---------------------------------------------------------------------------
-- 5. 用量、审计、检索日志、触发器日志
-- ---------------------------------------------------------------------------
-- 注意：以下三张表实际列名与旧脚本假设不同，按真实结构写
--   agent_token_usage     无 create_by，用 user_id
--   agent_kb_zero_hit_log 无 create_by，用 user_id
--   agent_audit_log       无 user_name，用 operator_name / operator_id
DELETE FROM agent_token_usage       WHERE user_id = @demo_user;
DELETE FROM agent_token_usage_daily WHERE user_id = @demo_user;
DELETE FROM agent_audit_log         WHERE operator_name = 'demo' OR operator_id = @demo_user;
DELETE FROM agent_kb_zero_hit_log   WHERE user_id = @demo_user;
DELETE FROM agent_kb_query_log      WHERE user_id = @demo_user;
DELETE FROM agent_trigger_log       WHERE create_by = @demo_user;

-- ---------------------------------------------------------------------------
-- 6. 市场安装：连同「安装时复制出来的 Agent」一起删，避免留下孤儿 Agent
--    （预置的 4 个演示 Agent 不在 agent_market_install 里，不受影响）
-- ---------------------------------------------------------------------------
DELETE FROM agent_info
 WHERE id IN (SELECT agent_copy_id FROM agent_market_install
               WHERE (user_id = @demo_user OR workspace_id = @demo_ws)
                 AND agent_copy_id IS NOT NULL);

DELETE FROM agent_market_install
 WHERE user_id = @demo_user OR workspace_id = @demo_ws;

-- ---------------------------------------------------------------------------
-- 7. 评测与发布门禁：只清「执行记录」，保留评测集/用例/门禁策略等配置
-- ---------------------------------------------------------------------------
DELETE FROM agent_eval_case_result
 WHERE run_id IN (SELECT id FROM agent_eval_run
                   WHERE create_by = @demo_user OR workspace_id = @demo_ws);

DELETE FROM agent_eval_run
 WHERE create_by = @demo_user OR workspace_id = @demo_ws;

-- D2 回流用例：由 demo 空间会话采样/标记而来，会话已删则必须一并清理，
-- 否则演示环境会残留指向已删会话的待确认样本。手工录入用例(source IS NULL)保留。
DELETE FROM agent_eval_case
 WHERE source IN ('cs_badcase', 'cs_sampled')
   AND dataset_id IN (SELECT id FROM agent_eval_dataset WHERE workspace_id = @demo_ws);

DELETE FROM release_gate_execution
 WHERE agent_id IN (SELECT id FROM agent_info WHERE workspace_id = @demo_ws);
