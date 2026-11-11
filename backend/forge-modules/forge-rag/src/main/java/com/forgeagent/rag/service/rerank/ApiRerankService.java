package com.forgeagent.rag.service.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
/**
 * 标准 Reranker API 重排序实现(POST {base}/v1/rerank 协议,兼容硅基流动/百炼/Jina/Cohere 风格)。
 * 一次请求对全部候选打分,延迟与成本远低于逐条 LLM 打分;
 * 返回的 relevance_score 为 0-1,与向量相似度/相关度阈值口径天然一致。
 *
 * <p>路由约定:模型名包含 "rerank"(忽略大小写,如 bge-reranker-v2-m3/gte-rerank)时
 * 由 AgentKnowledgeServiceImpl 选用本实现,否则走 LlmRerankService 的 LLM 打分。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiRerankService implements RerankService {

    private final AgentModelMapper modelMapper;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 路由判定:模型名含 rerank 视为标准 Reranker API 模型
     */
    public static boolean supports(AgentModel model) {
        return model != null && model.getModelName() != null
            && model.getModelName().toLowerCase().contains("rerank");
    }

    @Override
    public List<RerankItem> rerank(String query, List<String> candidates, Long modelId) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (modelId == null) {
            throw new ServiceException("未配置重排序模型");
        }
        AgentModel model = modelMapper.selectById(modelId);
        if (model == null || StringUtils.isBlank(model.getApiKey())
            || StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getModelName())) {
            throw new ServiceException("重排序模型接入信息不完整:" + (model == null ? "模型不存在" : model.getName()));
        }
        String normalized = model.getBaseUrl().replaceAll("/+$", "");
        String endpoint = normalized.endsWith("/v1") ? normalized + "/rerank" : normalized + "/v1/rerank";

        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model.getModelName());
        req.put("query", query);
        ArrayNode docs = req.putArray("documents");
        // rerank 模型按全文打分,候选不做 LLM 式截断;超长文档由服务商自行截断
        for (String doc : candidates) {
            docs.add(doc == null ? "" : doc);
        }
        req.put("top_n", candidates.size());

        JsonNode resp = post(endpoint, model.getApiKey(), req);
        JsonNode results = resp.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new ServiceException("Reranker API 返回结果为空");
        }
        List<RerankItem> items = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            items.add(new RerankItem(index, node.path("relevance_score").asDouble(0)));
        }
        items.sort(Comparator.comparingDouble(RerankItem::score).reversed());
        return items;
    }

    private JsonNode post(String endpoint, String apiKey, ObjectNode req) {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
            String body = RestClient.builder()
                .requestFactory(factory)
                .build()
                .post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(MAPPER.writeValueAsString(req))
                .retrieve()
                .body(String.class);
            JsonNode resp = MAPPER.readTree(body);
            if (resp.has("code") && resp.path("code").asInt(-1) != 0) {
                throw new ServiceException("Reranker API 调用失败:" + resp.path("message").asText());
            }
            return resp;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("调用 Reranker API 失败:" + e.getMessage());
        }
    }
}
