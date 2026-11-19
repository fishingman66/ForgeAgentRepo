# backend/sql

**本目录已清空（2026-11-19）。**

## 为什么

系统尚未上线，数据库以「当前开发库」为唯一基线版本。原先这里的 95 个增量升级脚本
（`agent_*.sql` / `cs_*.sql` / `tenant_*.sql` 等）已完成历史使命：

- 全部**表结构**（139 表、全部列与索引）已折进 `deploy/docker/mysql/init/01-schema.sql`
- 全部**菜单与权限**已折进 `02-seed.sql`
- 全部**业务种子**（模型供应商、内置工具、客服意图/敏感词/质检规则、
  呼叫中心渠道/打断词/IVR/路由）已收口到 `04-seed-agent.sql`

新建库（Docker 或手工）只需按序执行 `init/` 下 01→02→03→04 四个文件，无需再逐个跑增量脚本。

## 历史版本

删除前的完整快照：`docs/archive/backend_sql_snapshot_20261119.zip`（95 个文件，118 KB）。
仅供追溯，**不要在新环境执行**——它们是分步增量，按序执行依赖当时的历史状态。

## 今后的演进方式

1. **只改基线**：结构变更直接改 `init/01-schema.sql` 的建表定义；
   种子变更改 `init/02-seed.sql` 或 `init/04-seed-agent.sql`。
2. **不再新增增量脚本**。系统未上线，没有需要在线升级的历史库。
3. 若将来上线后需要在线升级，重新建立 `migrations/` 目录并按版本号命名，
   不要恢复本目录这种「一次性全量脚本堆叠」的模式。

## 一致性校验

```bash
# 表 / 列 / 索引三级差异（对照开发库）
python ../../../scripts/sql/schema_drift_audit.py

# 空库全量回放（01~04），并断言业务种子行数
python ../../../scripts/sql/baseline_replay_verify.py
```

已知且接受的唯一差异：`flow_node.version`（Warm-Flow 引擎表，开发库是旧版残留，
加列会影响引擎行为，需随引擎升级单独评估）。
