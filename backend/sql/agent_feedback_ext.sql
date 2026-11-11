-- UX-02 消息反馈闭环:反馈备注/标签/时间 + 反馈管理菜单
-- 说明:feedback 列已存在(varchar, like/dislike/空),本脚本仅扩展备注、标签、时间与索引

ALTER TABLE agent_message
  ADD COLUMN feedback_comment VARCHAR(500) NULL COMMENT '反馈备注' AFTER feedback,
  ADD COLUMN feedback_tags VARCHAR(500) NULL COMMENT '反馈标签(逗号分隔)' AFTER feedback_comment,
  ADD COLUMN feedback_time DATETIME NULL COMMENT '反馈时间' AFTER feedback_tags,
  ADD INDEX idx_feedback_time(feedback, feedback_time);

-- ----------------------------
-- 菜单: 反馈管理(挂在 智能体 目录下)
-- ----------------------------
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values (1761400000000030701, '反馈管理', 1761400000000030001, 18, 'feedback', 'agent/feedback/index', null,
        'N', 'Y', 'C', '0', '0', 'agent:feedback:list', 'message', null,
        null, 1, now(), '助手消息赞踩反馈列表');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      create_dept, create_by, create_time, remark)
values
(1761400000000030702, '反馈查询', 1761400000000030701, 1, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:feedback:query', '#', null, null, 1, now(), ''),
(1761400000000030703, '反馈导出', 1761400000000030701, 2, '', null, null, 'N', 'Y', 'F', '0', '0', 'agent:feedback:export', '#', null, null, 1, now(), '');
