package com.forgeagent.rag.service.embedding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 嵌入模型工厂:按知识库配置选择内置或外部嵌入服务
 *
 * <p>P0/A4:local-hash 是特征哈希伪向量,只用于本地联调与会话附件临时索引。
 * 生产 profile 下正式知识库(kbScope != temp)禁止使用,必须为知识库配置真实嵌入模型;
 * 临时附件索引仍放行,避免上传文件功能在生产被误伤。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class EmbeddingFactory {

    private final KnowledgeProperties properties;
    private final AgentModelMapper modelMapper;
    private final Environment environment;
    private final Map<String, EmbeddingService> cache = new ConcurrentHashMap<>();

    /**
     * 按知识库配置获取嵌入服务
     */
    public EmbeddingService forKnowledge(AgentKnowledge knowledge) {
        String model = knowledge.getEmbeddingModel();
        if (StringUtils.isBlank(model) || LocalHashEmbeddingService.MODEL_KEY.equals(model)) {
            boolean tempIndex = "temp".equals(knowledge.getKbScope());
            if (isProduction() && !tempIndex) {
                throw new ServiceException("生产环境禁止使用 local-hash 伪向量,请为知识库["
                    + knowledge.getName() + "]配置真实嵌入模型");
            }
            if (isProduction()) {
                log.warn("[EmbeddingFactory] 会话附件临时索引 {} 使用 local-hash 伪向量,仅用于短期检索",
                    knowledge.getId());
            } else {
                log.warn("[EmbeddingFactory] 知识库 {} 使用 local-hash 伪向量(仅本地联调),检索结果不具备语义准确性",
                    knowledge.getName());
            }
            return cache.computeIfAbsent(LocalHashEmbeddingService.MODEL_KEY,
                k -> new LocalHashEmbeddingService(dim()));
        }
        return cache.computeIfAbsent(model, k -> buildExternal(model));
    }

    /**
     * P3 / M2':按模型名获取嵌入服务,供长期记忆等非知识库场景复用。
     *
     * <p>与 {@link #forKnowledge} 的差别:知识库在生产 profile 下禁止 local-hash(会抛异常),
     * 记忆召回属于增强能力,失败可降级回纯评分,因此这里只告警不阻断。</p>
     *
     * @param modelName local-hash 或 agent_model 中配置的 OpenAI 兼容嵌入模型名,空值按 local-hash
     */
    public EmbeddingService forModel(String modelName) {
        if (StringUtils.isBlank(modelName) || LocalHashEmbeddingService.MODEL_KEY.equals(modelName)) {
            if (isProduction()) {
                log.warn("[EmbeddingFactory] 记忆向量使用 local-hash 伪向量,不具备语义准确性,"
                    + "请配置 memory.vector.model 指向真实嵌入模型");
            }
            return cache.computeIfAbsent(LocalHashEmbeddingService.MODEL_KEY,
                k -> new LocalHashEmbeddingService(dim()));
        }
        return cache.computeIfAbsent(modelName, k -> buildExternal(modelName));
    }

    /**
     * 解析知识库应使用的向量维度:外部模型以真实输出为准,local-hash 取全局配置。
     *
     * <p>建库 / 重建索引 / 校验模型切换都用这一个入口,避免各处各自读全局 {@code vector-dim}
     * 得出互相矛盾的维度。</p>
     *
     * @param knowledge 知识库(读 embeddingModel / kbScope)
     * @return 实际向量维度;探测失败时回退全局配置并告警
     */
    public int resolveDimension(AgentKnowledge knowledge) {
        EmbeddingService service = forKnowledge(knowledge);
        int dim = service.dimension();
        if (dim > 0) {
            return dim;
        }
        dim = service.warmUp();
        if (dim > 0) {
            return dim;
        }
        log.warn("[EmbeddingFactory] 知识库 {} 的嵌入模型 {} 维度探测失败,回退默认维度 {};"
                + "该维度可能与模型实际输出不一致,请在模型可用后执行重建索引",
            knowledge.getName(), service.modelKey(), dim());
        return dim();
    }

    /**
     * 按名称查找启用的模型配置并构建 OpenAI 兼容嵌入服务
     */
    private EmbeddingService buildExternal(String modelName) {
        AgentModel cfg = modelMapper.selectOne(new LambdaQueryWrapper<AgentModel>()
            .eq(AgentModel::getName, modelName)
            .eq(AgentModel::getStatus, "0")
            .last("limit 1"));
        if (cfg == null) {
            throw new ServiceException("嵌入模型配置不存在或已停用:" + modelName);
        }
        if (StringUtils.isBlank(cfg.getBaseUrl()) || StringUtils.isBlank(cfg.getApiKey())) {
            throw new ServiceException("嵌入模型配置缺少 baseUrl 或 apiKey:" + modelName);
        }
        // 维度传 0:由模型首次返回自校准,不再假定全局 vector-dim
        return new OpenAiEmbeddingService(modelName, cfg.getBaseUrl(), cfg.getApiKey(),
            cfg.getModelName(), 0);
    }

    /**
     * 默认维度:仅供 local-hash 伪向量构造与外部模型探测失败时兜底,
     * <b>不再作为外部模型的期望维度</b>(外部模型以实际输出为准,见 {@link #resolveDimension})
     */
    private int dim() {
        Integer configured = properties.getVectorDim();
        return configured == null || configured <= 0 ? 384 : configured;
    }

    private boolean isProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
