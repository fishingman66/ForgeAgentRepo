package com.forgeagent.rag.service.rerank;

import java.util.List;
/**
 * 检索结果重排序(精排)服务
 *
 * <p>在混合检索粗排召回的基础上,对候选片段与查询的相关性做二次打分排序,
 * 提升 Top1 准确度。打分失败时由调用方回退粗排结果,不阻断检索主链路。</p>
 *
 * @author ForgeAgent
 */
public interface RerankService {

    /**
     * 对候选文本按与查询的相关性打分并降序重排
     *
     * @param query      用户查询
     * @param candidates 候选文本列表(返回结果的 index 即此列表下标)
     * @param modelId    重排序使用的模型配置ID(agent_model 主键)
     * @return 按分数降序的重排结果;无法打分时抛出异常由调用方回退
     */
    List<RerankItem> rerank(String query, List<String> candidates, Long modelId);

    /**
     * 单条重排结果:候选下标 + 相关性分数
     */
    record RerankItem(int index, double score) {
    }
}
