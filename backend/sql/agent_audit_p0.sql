-- =====================================================================
-- Agent 平台 · 审计日志 P0 扩展(字段扩展 + 事件覆盖补全)
-- 目标库: forge-vue
-- 说明: 在原有 agent_audit_log 基础上扩展来源/结果/级别/变更快照等字段
-- =====================================================================

-- 1. 表结构扩展
alter table agent_audit_log
    add column source_channel varchar(20)  default 'web'  comment '来源 web/api_key/system' after target_id,
    add column client_ip      varchar(50)  default ''     comment '客户端IP' after source_channel,
    add column user_agent     varchar(255) default ''     comment 'User-Agent' after client_ip,
    add column result         char(1)      default '0'    comment '操作结果(0成功 1失败)' after user_agent,
    add column severity       varchar(10)  default 'INFO' comment '事件级别(INFO/WARN/RISK)' after result,
    add column trace_id       varchar(64)  default ''     comment '链路追踪ID' after severity,
    add column old_value      text         null           comment '变更前快照(JSON)' after trace_id,
    add column new_value      text         null           comment '变更后快照(JSON)' after old_value;

-- 2. 新增索引
alter table agent_audit_log
    add key idx_severity (severity),
    add key idx_trace (trace_id),
    add key idx_result (result);

-- 3. 历史数据回填(统一标记为 web / INFO / 成功)
update agent_audit_log
set source_channel = 'web',
    severity = 'INFO',
    result = '0'
where severity is null or severity = '';
