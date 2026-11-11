-- =====================================================================
-- Agent 平台 · 父子分块(small-to-big)
-- 目标库: forge-vue
-- 说明: 开启后文档先按 parent_chunk_size 切父块,再在每个父块内按
--       chunk_size/overlap 切子块;子块参与嵌入与召回,命中后注入父块全文,
--       兼顾检索精度与上下文完整性。存量分块 chunk_role 默认 c,行为不变
-- =====================================================================

alter table agent_kb_chunk
    add column chunk_role char(1) not null default 'c' comment '分块角色(c=检索子块 p=父块上下文块)',
    add column parent_id bigint null comment '父块ID(chunk_role=c 时指向所属父块)';

alter table agent_knowledge
    add column parent_chunk_enabled char(1) not null default '0' comment '父子分块开关(1=先切父块再切子块,检索命中子块注入父块全文)',
    add column parent_chunk_size int null default 2000 comment '父块目标大小(字符)';
