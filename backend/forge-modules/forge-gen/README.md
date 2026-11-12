# forge-gen · 代码生成

基于 FreeMarker 模板的代码生成器：读表结构 → 生成后端 Java（domain / bo / vo / mapper / service / controller / XML）与前端（Vue / React 的 api.ts / types.ts / index.vue），以及 MySQL / PostgreSQL 建表 SQL。13 个主类、1 个 Controller（`GenController`）、无单元测试。

依赖：`forge-common-core` / `-doc` / `-mybatis` / `-web` / `-log`。**不依赖任何 Agent 业务模块**，是独立的开发工具模块。

## 结构

```
com.forgeagent.gen
├── config/           # GenProperties / GenConstants / MyBatisDataSourceMonitor
├── controller/       # GenController（表列表 / 导入 / 预览 / 生成 / 下载）
├── domain/           # GenTable / GenTableColumn
├── mapper/           # GenTableMapper / GenTableColumnMapper（含 XML）
├── service/          # IGenTableService / GenTableServiceImpl
└── utils/            # GenUtils / TemplateEngineUtils / PathNamedTemplate

src/main/resources/
├── generator.yml                 # 生成配置（包名 / 作者 / 输出路径 / 模板开关）
├── fm/java/*.ftl                 # 后端模板：domain / bo / vo / mapper / service / serviceImpl / controller
├── fm/xml/mapper.xml.ftl         # Mapper XML
├── fm/vue/*.ftl                  # Vue 模板：api.ts / types.ts / index.vue / index-tree.vue
├── fm/react/*.ftl                # React 模板
└── fm/sql/mysql.sql.ftl / postgres.sql.ftl
```

## 使用约定

- 生成的代码遵循本项目分层：`domain` / `domain/bo` / `domain/vo` / `mapper` / `service` + `service/impl` / `controller`，与现有模块目录结构一致。
- **新增 Vo 必须挂 `@AutoMapper(target = Entity.class)`**：`BaseMapperPlus#selectVoPage` 靠 MapStruct Plus 生成的转换器，漏注解会抛 `ConvertException: cannot find converter from X to Y`，而接口只回「发生未知异常 + 错误编号」。模板生成后请检查这一项。
- 生成物是**脚手架**，需要按平台约定补齐：空间归属（`workspace_id`）/ `ResourceAccessGuard` 可见性校验、审计日志、权限注解 `@SaCheckPermission`、按钮权限 `v-hasPermi`、以及对应的幂等增量 SQL。
- 本模块生成的 Vue 模板是通用 CRUD；Agent 平台页面（如知识库治理弹窗、坐席辅助看板）有各自的交互约定，不要直接套用。

## 数据初始化

代码生成表 `gen_table` / `gen_table_column` 在基座脚本 `backend/script/sql/mysql/forge_vue.sql`（postgres 版本在 `script/sql/postgres/`）。

> 生成的 `.vue` 模板必须满足项目「组件单根节点」约定（`check-transition` 插件）：并列表单项用容器包裹，需要并列布局时用 `display: contents`。
