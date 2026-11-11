Third-Party Notices
===================

> 适用版本：6.1.0（基线版） · 清单核对日期：2026-11-11
> 依赖版本以 `backend/pom.xml` 的 `dependencyManagement` 为准；各组件的许可证以其官方仓库声明为准，如有出入以上游为准。

## 关于本产品

智造 ForgeAgent 是一个企业级智能体平台，包含自研的 Agent 运行与治理层（工作空间、编排、对话、市场、审计计量）、知识库检索增强层、长期记忆体系（六层）与知识图谱模块（forge-kg）、智能客服域与语音呼叫中心、工作流引擎以及前端交互体系。产品的构建受益于下列开源项目，谨致谢意。

依据 MIT 协议要求，以下保留所衍生的上游项目的原作者版权声明与许可文本：

1. LoomAgent（主要的参考项目）
   Copyright (c) 2026 mtour
   https://gitee.com/mtour/loom-agent
   许可协议：MIT License（全文见下方）

2. RuoYi-Vue-Plus（后端企业级开发基座）
   Copyright (c) dromara / JavaLionLi
   https://gitee.com/dromara/RuoYi-Vue-Plus
   许可协议：MIT License（全文见下方）

3. plus-ui（前端管理界面基座）
   Copyright (c) JavaLionLi
   https://gitee.com/JavaLionLi/plus-ui
   许可协议：MIT License（全文见下方）

## 第三方依赖清单

以下为后端各模块**直接声明**的第三方依赖（按许可证分组；传递依赖未逐一列出，以其各自声明为准）。

### 运行内核与 AI 集成

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| io.agentscope:agentscope | Apache-2.0 | Agent 运行内核（ReAct 循环、流式事件、钩子机制） |
| org.springframework.ai:spring-ai-* | Apache-2.0 | MCP 客户端 / 服务端集成 |
| io.modelcontextprotocol.sdk:mcp | MIT | MCP 协议 Java SDK |

### dromara 社区组件

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| cn.dev33:sa-token-*（含 sa-token-jwt） | MIT | 登录认证与权限 |
| cn.hutool:hutool-* | MulanPSL-2.0 | 工具集（core/extra/http/crypto/captcha） |
| com.baomidou:mybatis-plus-*、dynamic-datasource、lock4j | Apache-2.0 | ORM、多数据源、分布式锁 |
| org.dromara.easy-es:easy-es-boot-starter | Apache-2.0 | Elasticsearch ORM（知识库全文检索） |
| org.dromara.mica-mqtt | Apache-2.0 | MQTT 客户端 |
| org.dromara.sms4j | Apache-2.0 | 短信发送 |
| com.aizuda:snail-job-* | Apache-2.0 | 定时任务调度（snail-job server / client） |
| com.alibaba.fastjson2 | Apache-2.0 | JSON 处理（社交登录） |
| cn.herodotus.opensteward:JustAuth | MIT | 第三方社交登录 |

### 数据访问与存储

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| org.mybatis:mybatis | Apache-2.0 | SQL 映射 |
| com.github.yulichang:mybatis-plus-join | Apache-2.0 | 连表查询 |
| com.mysql:mysql-connector-j | GPL-2.0-only + Universal FOSS Exception（Oracle 双许可） | MySQL 驱动 |
| org.postgresql:postgresql | PostgreSQL License (BSD-2-Clause) | PostgreSQL 驱动（框架基座支持） |
| org.redisson:redisson-* | Apache-2.0 | Redis 客户端与分布式对象 |
| com.github.ben-manes.caffeine:caffeine | Apache-2.0 | 本地缓存 |
| software.amazon.awssdk:s3 / s3-transfer-manager / netty-nio-client | Apache-2.0 | 对象存储（OSS 兼容协议） |
| org.elasticsearch.client:elasticsearch-java / co.elastic.clients | Elastic License 2.0 或 AGPL-3.0（双许可） | 全文检索客户端 |

### 工作流与流程

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| io.github.sgs98:warm-flow-* | Apache-2.0 | OA 审批流程引擎（forge-workflow） |
| com.yomahub:liteflow | Apache-2.0 | 规则编排 |

### 文档、报表与工具库

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| org.apache.pdfbox:pdfbox | Apache-2.0 | PDF 解析 |
| org.apache.fesod:fesod-sheet | Apache-2.0 | Excel 导入导出 |
| org.lionsoul:ip2region | Apache-2.0 | IP 属地解析 |
| io.github.linpeilie:mapstruct-plus | Apache-2.0 | 对象映射 |
| org.projectlombok:lombok | MIT | 编译期代码生成 |
| org.bouncycastle:bcpkix-jdk18on | MIT | 加解密（渠道密钥 AES-GCM 等） |
| org.apache.fory:fory-core | Apache-2.0 | 高性能序列化 |
| com.github.therapi:therapi-runtime-javadoc | Apache-2.0 | 接口文档注释提取 |
| org.scala-lang:scala-library | Apache-2.0 | SnailJob server 运行时 |
| org.anyline:anyline-* | Apache-2.0 | 代码生成（多方言表结构读取） |
| org.freemarker:freemarker | Apache-2.0 | 代码生成模板 |
| org.apache.skywalking:apm-toolkit-* | Apache-2.0 | 链路追踪埋点 |

### 框架底座

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| Spring Boot / Spring Framework / Spring Security（含 jetty / webflux 相关 starter） | Apache-2.0 | 应用基座 |
| tools.jackson.core:jackson-databind、com.fasterxml.jackson.* | Apache-2.0 | JSON 序列化 |
| de.codecentric:spring-boot-admin-* | Apache-2.0 | 监控面板（forge-monitor-admin） |
| org.springdoc:springdoc-openapi | Apache-2.0 | 接口文档 |
| jakarta.servlet / jakarta.mail-api / org.eclipse.angus:jakarta.mail | EPL-2.0 或 GPL-2.0-with-classpath-exception（Jakarta EE） | Servlet 与邮件 |
| org.apache.commons:commons-lang3 | Apache-2.0 | 通用工具 |

### 可选运行时

| 组件 | 许可证 | 说明 |
| --- | --- | --- |
| Milvus Java SDK | Apache-2.0 | 启用 Milvus 向量库时引入；不部署则知识库走 MySQL 向量列 / 全文检索，其余功能不受影响 |

### 前端

前端基于 plus-ui（MIT，见上），其直接依赖（Vue 3、Element Plus、Pinia、Axios、vue-i18n 等为 MIT；ECharts 为 Apache-2.0）以 `frontend/package.json` 为准，随构建产物分发时遵循各自许可证。

### 数据库脚本

平台业务表结构与菜单脚本（`backend/sql/agent_*.sql`、`forge_kg_core.sql`）均为本项目自研产出，遵循本项目的 MIT 协议；框架基座表结构（`backend/script/sql/mysql/`）沿用 RuoYi-Vue-Plus。

## MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
