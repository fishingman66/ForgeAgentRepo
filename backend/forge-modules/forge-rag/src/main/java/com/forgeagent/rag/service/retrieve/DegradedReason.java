package com.forgeagent.rag.service.retrieve;

/**
 * 检索降级原因。
 *
 * <p>降级本身不可怕("向量库挂了就先用关键词"是合理设计),可怕的是<b>降级不留痕</b>:
 * 结果变少、变差,现场只能猜。每条降级都要能被结构化记录,并在检索日志里与这次 query 绑定。</p>
 *
 * <p>命名口径与 kb-rag-project 的 {@code DegradedReason} 对齐(它 13 个,这里按 ForgeAgent
 * 实际分支裁剪成 8 个),便于后续互相对照。</p>
 *
 * @author ForgeAgent
 */
public enum DegradedReason {

    /**
     * 请求了向量路但嵌入服务不可用 / 维度不匹配 / 向量库不可用
     */
    VECTOR_ROUTE_UNAVAILABLE("vector_route_unavailable"),

    /**
     * 全文检索执行失败(SQL/索引异常),本次只有向量路
     */
    KEYWORD_ROUTE_FAILED("keyword_route_failed"),

    /**
     * 开了精排但知识库未配置重排序模型,或模型已停用
     */
    RERANK_UNAVAILABLE("rerank_unavailable"),

    /**
     * 精排超时(在线 1.5s),已回退 RRF 粗排
     */
    RERANK_TIMEOUT("rerank_timeout"),

    /**
     * 精排异常、线程池拒绝、返回条数与提交不一致
     */
    RERANK_ERROR("rerank_error"),

    /**
     * 配了阈值但本次没有可比分数(只命中全文),阈值未生效
     */
    THRESHOLD_INACTIVE("threshold_inactive"),

    /**
     * 父子分块模式下父块已不存在,回退为子块原文
     */
    PARENT_MISSING("parent_missing"),

    /**
     * 检索缓存不可用(Redis 异常),本次直查数据库与向量库
     */
    CACHE_BYPASS("cache_bypass");

    private final String code;

    DegradedReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
