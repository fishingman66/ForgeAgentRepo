# forge-job · 定时任务示例模块

**SnailJob** 分布式任务调度的接入示例模块。11 个类、无 Controller、无单元测试。当前内容以**演示 / 样例**为主（账单任务 + 各类执行器写法），平台自身的业务逻辑不放在这里。

依赖：`forge-common-json` / `forge-common-job`。配套独立进程 `forge-extend/snailjob-server`（调度服务端）。

## 内容

| 类 | 说明 |
| --- | --- |
| `AlipayBillTask` / `WechatBillTask` / `SummaryBillTask` | 账单类任务示例（对账 / 汇总），`BillDTO` 为入参 |
| `TestAnnoJobExecutor` | 注解式执行器 |
| `TestClassJobExecutor` | 类式执行器 |
| `TestBroadcastJob` | 广播任务 |
| `TestStaticShardingJob` | 静态分片 |
| `TestMapJobAnnotation` / `TestMapReduceAnnotation1` | Map / MapReduce 分布式计算 |
| `package-info` | 包说明 |

## 平台里真正的定时任务在哪

本模块**不承载**平台业务调度。各业务模块的定时任务用 Spring `@Scheduled` + 独立 boolean 开关实现，落在各自模块：

| 位置 | 任务 |
| --- | --- |
| `forge-rag` | `KbVectorSyncService` 向量写失败补偿（30s / batch 200 / 上限 5 次）、`DocTrashCleanupService` 回收站超期清理 |
| `forge-memory` | `MemoryGovernanceService` 归档 / 配额 / TTL 盘点（`scan-ms`） |
| `forge-agent` | 取消观察器、审批过期清扫、僵尸 running 兜底、Cron 触发器异步投递 |
| `forge-customer-service` | `CsSlaScheduler` SLA 扫描、零采纳回流（每日 2:30） |

`@Scheduled(fixedDelayString="${x:60000}")` 的表达式**不能配 0**——会抛 `IllegalArgumentException` 导致应用起不来。「0 = 关闭」必须用独立 boolean 开关（如 `sla-enabled`），不能靠把间隔设成 0。`spring.task.scheduling.pool.size=4` 用于隔离各任务故障域。

## 数据初始化

调度库 `forge-job` 独立建库，基座脚本 `backend/script/sql/mysql/forge_job.sql`。上线前更换 `application-prod.yml` 中的 snail-job token 与相关默认凭据。
