package com.forgeagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/**
 * 知识库文档与向量检索配置
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.knowledge")
public class KnowledgeProperties {

    /**
     * 向量存储实现:milvus(生产) / mysql(本地开发)。
     *
     * <p>mysql 模式把向量存进 {@code agent_kb_chunk.embedding} 并在应用层算余弦,
     * 目的是「不装 Milvus 也能跑通 RAG 全链路」;超过一万分块会明显变慢,不适合生产。</p>
     */
    private String vectorStore = "milvus";

    /**
     * Milvus 服务地址
     */
    private String milvusHost = "127.0.0.1";

    /**
     * Milvus 服务端口(REST/gRPC 复用端口)
     */
    private Integer milvusPort = 19530;

    /**
     * Milvus 访问令牌(单机版默认空)
     */
    private String milvusToken = "";

    /**
     * Milvus 集合名称(存放全部知识库分块向量,按 knowledge_id 过滤隔离)
     */
    private String collectionName = "agent_kb_chunk";

    /**
     * 向量维度(须与集合创建时一致)
     */
    private Integer vectorDim = 384;

    /**
     * 文档原文件存储目录
     */
    private String fileDir = "./data/knowledge";

    /**
     * 单文件大小上限(MB)
     */
    private Integer maxFileSizeMb = 20;

    /**
     * Milvus 请求超时(毫秒)
     */
    private Integer milvusTimeoutMs = 10000;

    /**
     * 对话 RAG 检索: 单库返回条数
     */
    private Integer ragTopK = 3;

    /**
     * 对话 RAG 检索: 最终注入条数上限
     */
    private Integer ragMaxHits = 5;

    /**
     * 对话 RAG 检索: 相关度阈值。
     *
     * <p><b>只作用于可比分数</b>:精排分(0~1)或向量余弦(-1~1);仅全文命中的分块不参与过滤。
     * 注意不要再拿它去比 RRF 融合分——RRF 只在单次 query 内可比,上限约 0.033,
     * 阈值设成 0.05 会导致检索恒为空(历史上生产默认值踩过这个坑)。</p>
     */
    private Double ragMinScore = 0.05;

    /**
     * 多库检索:跨库 RRF 的 k 值(score = 1/(k + 库内名次)),默认 60 与库内 RRF 口径一致
     */
    private Integer crossKbRrfK = 60;

    /**
     * 查询改写超时(毫秒)。改成短超时:改写是增强项,一次模型抖动不该把首 token 延迟拖到 30s
     */
    private Integer rewriteTimeoutMs = 2000;

    /**
     * 对话 RAG 检索: 多轮对话查询改写(有历史时先把用户问题改写为独立检索查询,提升多轮召回率)
     */
    private Boolean queryRewriteEnabled = true;

    /**
     * 对话 RAG 检索: 注入上下文 token 预算(按估算 token 计,超预算的低分命中不注入,避免挤爆模型上下文)
     */
    private Integer ragContextBudget = 2000;

    /**
     * 对话 RAG 检索: 邻块扩展(命中分块前后各补 1 个同文档启用分块,补全孤立片段的上下文)
     */
    private Boolean ragNeighborExpand = true;

    /**
     * 对话 RAG 检索: 结果缓存开关(仅对话链路生效,命中测试不缓存保证调试实时性)
     */
    private Boolean ragCacheEnabled = true;

    /**
     * R3-4 重建门禁:开启后重建索引/差异重建前会检查最近两次评测运行,
     * BLOCKED(指标回归超 ε)则拒绝;默认关,先观察再打开
     */
    private Boolean gateRequired = false;

    /**
     * 对话 RAG 检索: 结果缓存时长(秒);分块编辑/禁用后最长该秒数内生效
     */
    private Integer ragCacheTtlSeconds = 60;

    /**
     * R4-2 引用校验开关:绑定了知识库的对话回答在落库/done 前校验 [n] 引用编号,
     * 越界即失败(非流式抛异常;流式发 error 终态);默认开,发现误伤可临时关闭
     */
    private Boolean citationValidateEnabled = true;

    /**
     * R5-2 回收站清理开关与保留天数:入回收站超过保留期的文档由定时任务物理删除
     */
    private Boolean trashCleanupEnabled = true;

    private Integer trashRetentionDays = 30;

    /**
     * R5-3 图谱辅助检索:作为 RRF 第三路开启(默认关,dev 可开);融合只允许 RRF
     */
    private Boolean graphRouteEnabled = false;

    /**
     * 图扩跳上限(1~2 跳;跳数越多越容易跑偏)
     */
    private Integer graphMaxHops = 2;

    /**
     * 实体匹配上限与图路回填分块上限
     */
    private Integer graphEntityLimit = 10;

    private Integer graphChunkLimit = 20;

    /**
     * R5-3 摄取侧实体抽取:默认关;需配 kg.extract-model(agent_model.model_name)
     */
    private Boolean kgExtractEnabled = false;

    private String kgExtractModel = "";

    /**
     * 单文档最多抽取多少个分块(1 分块 1 次模型调用,控制成本)
     */
    private Integer kgExtractMaxChunks = 60;

    /**
     * R5-5 图片描述:全局开关(还需库级 vlm_enabled 同时打开);vlm-model 取 agent_model.model_name,
     * 必须是支持视觉输入的模型,未配置则只剥离标记不描述
     */
    private Boolean vlmEnabled = false;

    private String vlmModel = "";

    /**
     * 参与描述的最小图片边长(像素),小于该值视为图标/水印
     */
    private Integer vlmMinPixels = 200;

    /**
     * 敏感配置加密密钥(图片识别模型 API Key 用):生产必须显式配置,
     * 换密钥会导致既有密文无法解密(需重新保存配置)
     */
    private String secretKey = "forge-kb-secret-change-me";

    /**
     * 精排调用硬超时(毫秒)。精排是增强项,模型抖动不该把整轮检索拖到几十秒;超时回退 RRF 粗排
     */
    private Integer rerankTimeoutMs = 1500;

    /**
     * 送精排的候选上限(全局预算)。候选越多越准但也越慢越贵,50 是准确率与延迟的折中点
     */
    private Integer rerankCandidateLimit = 50;

    /**
     * 向量同步补偿开关(分块落库后向量写失败,由定时扫描重试)
     */
    private Boolean syncCompensationEnabled = true;

    /**
     * 单次补偿扫描处理的分块上限
     */
    private Integer syncCompensationBatchSize = 200;

    /**
     * 单条分块向量补偿最大重试次数(达上限放弃并留 FAILED,避免永久失败变成无限负载)
     */
    private Integer syncCompensationMaxRetry = 5;

    /**
     * PENDING 状态的宽容期(分钟):刚落库的分块可能正在写入,过了这个时间才让补偿接管
     */
    private Integer syncPendingTimeoutMinutes = 5;

    /**
     * MCP 检索端点访问令牌(非空时 MCP 调用须带 X-MCP-Token 请求头;空=不校验,仅建议内网使用)
     */
    private String mcpToken = "";

    /**
     * MCP 检索端点开关(默认关闭;开启后外部 MCP 客户端可通过 /mcp 端点检索知识库)
     */
    private Boolean mcpEnabled = false;
}
