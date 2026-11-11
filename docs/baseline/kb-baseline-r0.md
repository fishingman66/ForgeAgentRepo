# 知识库检索 R0 基线（2026-11-11 14:26:07）

> 用途：后续批次（R2 剩余 / R3 评测改造）改动前先与本文档对比，判断是变好还是变差。
> 复现：`python docs/baseline/kb_baseline.py`（自动建库、灌语料、跑用例、写回本文件；`--no-write` 只打印不覆盖）。
> 语料与用例：`docs/baseline/kb-baseline-corpus/` 与 `docs/baseline/kb-baseline-cases.json`。改语料等于改基线，须重跑并升版本号。

## 1. 环境与口径

| 项 | 值 |
| --- | --- |
| profile | dev |
| vectorStore | mysql(应用层余弦) |
| embeddingModel | local-hash(伪向量,仅供链路验证) |
| chunkSize | 500 |
| chunkOverlap | 50 |
| chunkUnit | char |
| splitStrategy | structure |
| parentChunk | False |
| topK | 5 |
| judge | 证据片段与命中内容去空白后包含匹配 |

## 2. 语料

| 文档数 | 就绪 | 分块数 | 有向量分块 | 待补偿 |
| --- | --- | --- | --- | --- |
| 6 | 6 | 36 | 36 | 0 |

## 3. 总体指标（Top-5）

| 指标 | 值 | 说明 |
| --- | --- | --- |
| HitRate@5 | 93.33% | 前 5 条里能否找到证据片段 |
| MRR | 0.8667 | 首个命中的倒数名次均值 |
| Recall@5 | 93.33% | 证据覆盖率（每例 1 条证据） |
| Precision@5 | 18.67% | 命中中真正相关的比例 |
| NDCG@5 | 0.8841 | 二值相关的排序质量 |

## 4. 分主题

| 主题 | 用例数 | HitRate | MRR | Precision@5 | NDCG@5 |
| --- | --- | --- | --- | --- | --- |
| 工作流 | 5 | 100% | 1.000 | 20% | 1.000 |
| 排障 | 5 | 80% | 0.800 | 16% | 0.800 |
| 权限 | 5 | 100% | 0.900 | 20% | 0.926 |
| 知识库 | 5 | 100% | 1.000 | 20% | 1.000 |
| 记忆 | 5 | 80% | 0.500 | 16% | 0.579 |
| 部署 | 5 | 100% | 1.000 | 20% | 1.000 |

## 5. 逐用例

| # | 问题 | 名次 | 命中数 | 首条分/口径 | 首条文档 | 通道 |
| --- | --- | --- | --- | --- | --- | --- |
| c01 | 端口跟配置文件里写的不一样，怎么查？ | 1 | 5 | 0.0328/cosine | 01-部署与运行.md | both/both/both/both/vector |
| c02 | 生产环境有哪些专属开关？ | 1 | 5 | 0.0328/cosine | 01-部署与运行.md | both/both/keyword/vector/vector |
| c03 | 不装向量库的时候向量存在哪里？ | 1 | 5 | 0.0323/cosine | 01-部署与运行.md | both/both/vector/vector/keyword |
| c04 | 中文全文检索需要什么分词器？ | 1 | 5 | 0.0328/cosine | 01-部署与运行.md | both/both/both/both/both |
| c05 | Redis 没起来会有什么影响？ | 1 | 5 | 0.0325/cosine | 01-部署与运行.md | both/both/both/vector/vector |
| c06 | 工作空间里有哪几种角色？ | 1 | 5 | 0.0328/cosine | 02-权限模型.md | both/both/both/both/both |
| c07 | 平台共享的资源谁能改？ | 2 | 5 | 0.0325/cosine | 02-权限模型.md | both/both/both/both/keyword |
| c08 | 私有资源能改成共享的吗？ | 1 | 5 | 0.0328/cosine | 02-权限模型.md | both/both/both/both/both |
| c09 | 列表查询的可见性条件要放在哪一层？ | 1 | 5 | 0.0328/cosine | 02-权限模型.md | both/both/both/vector/keyword |
| c10 | 登录令牌多久过期？ | 1 | 5 | 0.0328/cosine | 02-权限模型.md | both/both/both/both/vector |
| c11 | 向量维度谁来定？ | 1 | 5 | 0.0328/cosine | 03-知识库手册.md | both/both/both/both/both |
| c12 | 分块切太大有什么坏处？ | 1 | 5 | 0.0328/cosine | 03-知识库手册.md | both/both/both/both/both |
| c13 | 父块默认多大？ | 1 | 5 | 0.0328/cosine | 03-知识库手册.md | both/both/both/both/keyword |
| c14 | 重建索引的时候还能搜吗？ | 1 | 5 | 0.0325/cosine | 03-知识库手册.md | both/both/both/both/both |
| c15 | 为什么阈值配了反而什么都搜不到？ | 1 | 5 | 0.0323/cosine | 03-知识库手册.md | both/both/both/vector/keyword |
| c16 | 画布上一共几种节点？ | 1 | 5 | 0.0328/cosine | 04-工作流编排.md | both/both/both/both/keyword |
| c17 | 节点重试最多几次？ | 1 | 5 | 0.0328/cosine | 04-工作流编排.md | both/both/both/both/both |
| c18 | 条件节点支持哪些运算符？ | 1 | 5 | 0.0328/cosine | 04-工作流编排.md | both/both/both/both/vector |
| c19 | 定时触发怎么防止重复执行？ | 1 | 5 | 0.0328/cosine | 04-工作流编排.md | both/both/both/both/vector |
| c20 | 工作流跑挂了会不会自动转普通问答？ | 1 | 5 | 0.0328/cosine | 04-工作流编排.md | both/both/both/both/vector |
| c21 | 为什么接口失败也返回 200？ | 1 | 5 | 0.0328/cosine | 05-故障排查.md | both/both/both/vector/keyword |
| c22 | 文档显示可用但搜不到内容是什么原因？ | 1 | 5 | 0.0328/cosine | 05-故障排查.md | both/both/both/keyword/vector |
| c23 | 查询改写失败了整轮对话会失败吗？ | 1 | 5 | 0.0328/cosine | 05-故障排查.md | both/both/both/vector/keyword |
| c24 | 后台任务里检索为什么要传工作空间？ | — | 5 | 0.0323/cosine | 02-权限模型.md | both/both/both/both/vector |
| c25 | 拿到错误编号之后怎么定位问题？ | 1 | 5 | 0.0328/cosine | 05-故障排查.md | both/vector/keyword/vector/keyword |
| c26 | 同一个事实有新旧两个值会怎么处理？ | 2 | 5 | 0.0320/cosine | 03-知识库手册.md | both/both/both/both/vector |
| c27 | 记忆是按什么规则挑出来注入的？ | 2 | 5 | 0.0325/cosine | 06-记忆与图谱.md | both/both/both/keyword/vector |
| c28 | 删除一个用户的记忆会连带删什么？ | — | 5 | 0.0328/cosine | 06-记忆与图谱.md | both/both/both/both/both |
| c29 | 图谱里重复的节点和关系怎么处理？ | 1 | 5 | 0.0325/cosine | 06-记忆与图谱.md | both/both/both/both/vector |
| c30 | 过期数据是删掉还是保留？ | 2 | 5 | 0.0328/cosine | 06-记忆与图谱.md | both/both/both/both/vector |

## 6. 已知局限（读数字前必看）

1. **dev 环境用的是 local-hash 伪向量**：向量路基本等同于噪声，命中主要靠全文关键词路。这份基线衡量的是「分块 + 关键词 + 融合 + 阈值」这条链，不代表生产语义召回质量。
2. 判定口径是「证据片段去空白后包含于命中分块」，所以跨分块边界的证据会判未命中；语料里每条证据都刻意选在单个段落内，减少这种噪声。
3. 每例只有 1 条证据，Recall 与 HitRate 在当前口径下等价；R3 引入多证据用例后会分开。
4. 数字只用于**同环境前后对比**（改分块、改阈值、改融合），不要拿去和别的系统比。
5. **可复现的三个前提**（2026-11-11 R2-3 实测确认）：① 分块候选同分要有破平（RRF 按 chunkId、全文 SQL 按 id、本地向量按 id，均已加）；② 测量夹具要**串行摄取**，并发上传会让不同文档的分块 id 交错，同分顺序随之漂移；③ 每轮开始前 `OPTIMIZE TABLE agent_kb_chunk`——InnoDB 全文索引对已删行是异步清理的，上一轮残留会改变 IDF，导致同样的语料算出不同分数（实测会让 MRR 每轮漂 0.01~0.02）。三条都满足后连跑两次指标逐位相同。

## 7. 已知未命中（后续批次的回归靶子）

| # | 问题 | 现象 | 初步判断 |
| --- | --- | --- | --- |
| c24 | 后台任务里检索为什么要传工作空间？ | 目标「故障排查」排在第 2，命中的是它的别的分块 | 问题说"后台任务/工作空间"，原文写"内部链路/登录态"，字面差异大 → 语义召回缺口，真实嵌入模型应能改善 |
| c28 | 删除一个用户的记忆会连带删什么？ | 前 5 条有 4 条来自「记忆与图谱」，但没有一条是"遗忘是级联操作" | 同文档内相邻块（归档/配额）更贴"删除"这个动词；邻块扩展发生在 Top-K **之后**，救不回 Top-K 之外的块 |

## 8. 分块口径对照（R2-3 实测）

同一套语料、同一套用例，只改知识库的分块口径：

| 口径 | 分块数 | 平均字符 | 平均 token | HitRate@5 | MRR | NDCG@5 |
| --- | --- | --- | --- | --- | --- | --- |
| `char` 500 / 50（定版） | 36 | 139 | 120 | 93.33% | 0.8667 | 0.8841 |
| `token` 400 / 60 | 36 | 139 | 120 | 93.33% | 0.8667 | 0.8841 |

**结论：在这套语料上两种单位结果完全一致，因为预算根本没成为约束。** 语料是标题密集的手册类文档，
每个 `##` 小节只有 100~200 字，`标题必开新块`先一步决定切点，400 也好 500 也好都用不上。
token 计价真正起作用的是**无标题长文**与**中英混排**：中文 1 字≈1 token、英文 1 词≈1 token，
同样的数字在两种语言下的实际长度差 3~4 倍（`TextChunkerTest` 里有对应断言）。

因此产品默认仍保持 `char`（改默认值会影响存量库行为），需要时按库显式配置 `chunkUnit: token`。
要验证 token 的召回收益，得另建一套"长段落 / 英文混排"的语料——留给 R3 扩充评测集时一起做。

## 9. 复现步骤

```powershell
# 1. 确保 MySQL(3306)、Redis(6379) 在跑，18093 端口空闲
# 2. 打包（若 jar 不是最新）
python temp/run_mvn.py package temp/pkg_baseline.txt
# 3. 跑基线：写回 kb-baseline-r0.{json,md}
python docs/baseline/kb_baseline.py
# 4. 只校验不覆盖（每批改动后的回归）
python docs/baseline/kb_baseline.py --no-write
# 5. 分块口径对照实验
python docs/baseline/kb_baseline.py --no-write --unit token
python docs/baseline/kb_baseline.py --no-write --strategy fixed
```

脚本支持 `--unit char|token` / `--size N` / `--overlap N` / `--strategy structure|fixed` / `--no-write`。
夹具内部已固定三件事：串行摄取、每轮 `OPTIMIZE TABLE agent_kb_chunk`、建库后打印库内实际分块配置（便于核对参数真的生效）。

产物：
- `kb-baseline-r0.json`：逐用例明细（名次、命中数、首条分与口径、通道、命中文档列表），后续批次 diff 两份 JSON 即可看出哪些用例变了。
- `kb-baseline-r0.md`：本文件。
