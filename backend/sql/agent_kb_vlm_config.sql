-- =====================================================================
-- 知识库图片识别模型配置(R5-5 增强):按工作空间配置
-- 目标库: forge-vue;幂等
-- 解析优先级:工作空间行 > 平台默认行(workspace_id=0) > 全局 rag.knowledge.vlm-model
-- source_type=PLATFORM 复用 agent_model 凭证;CUSTOM 用本表 base_url + api_key_cipher
-- =====================================================================

create table if not exists agent_kb_vlm_config
(
    id             bigint        not null auto_increment comment '主键',
    workspace_id   bigint        not null comment '工作空间ID;0=平台默认(所有空间生效)',
    source_type    varchar(20)   not null default 'PLATFORM' comment 'PLATFORM=平台已配置模型(agent_model) / CUSTOM=OpenAI 兼容自定义',
    model_name     varchar(128)  null comment '模型标识(PLATFORM 为 agent_model.model_name)',
    base_url       varchar(500)  null comment 'CUSTOM 模式的基础地址(如 https://api.xxx.com/v1)',
    api_key_cipher varchar(1000) null comment 'API Key 密文(AES-GCM,ENC: 前缀),任何接口不回显明文',
    enabled        tinyint(1)    not null default 1 comment '是否启用',
    update_by      bigint        null comment '最后修改人',
    create_time    datetime      null,
    update_time    datetime      null,
    primary key (id),
    unique key uq_vlm_workspace (workspace_id)
) engine = innodb comment = '知识库图片识别模型配置(按工作空间)';
