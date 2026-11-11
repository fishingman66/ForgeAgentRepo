package com.forgeagent.rag.service.embedding;

import java.util.List;
/**
 * 文本向量化服务
 *
 * @author ForgeAgent
 */
public interface EmbeddingService {

    /**
     * 模型标识(local-hash 或 agent_model 配置名称)
     */
    String modelKey();

    /**
     * 输出向量维度;返回 -1 表示尚未确定(外部模型在首次调用前不知道真实维度)
     */
    int dimension();

    /**
     * 主动探测真实维度并缓存(本地模型直接返回 {@link #dimension()})
     *
     * <p>外部模型的维度以服务端实际返回为准,不能再用全局 `vector-dim` 假定——
     * 否则换一个 1024 维模型会被直接判为"维度不一致",或者更糟:写进维度不符的集合后被吞掉。</p>
     *
     * @return 探测到的维度;探测失败返回 -1
     */
    default int warmUp() {
        return dimension();
    }

    /**
     * 批量向量化文本
     *
     * @param texts 文本列表
     * @return 与输入等长的向量列表
     */
    List<float[]> embed(List<String> texts);
}
