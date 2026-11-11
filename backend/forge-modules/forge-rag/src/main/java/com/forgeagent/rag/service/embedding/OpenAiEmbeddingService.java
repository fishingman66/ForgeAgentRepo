package com.forgeagent.rag.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.List;
/**
 * OpenAI 兼容嵌入模型服务(/v1/embeddings 协议)
 *
 * <p>健壮性保障:连接/读取超时防护(防嵌入服务卡死拖垮摄取线程)、
 * 大文档按批切分请求(单批 32 条,避免请求体超限)、
 * 网络类失败指数退避重试(维度/数量校验失败属配置问题,不重试)。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
public class OpenAiEmbeddingService implements EmbeddingService {

    /**
     * 单次请求最大输入条数(请求体可控,超限自动续批)
     */
    private static final int BATCH_SIZE = 32;

    /**
     * 网络类失败最大尝试次数(含首次)
     */
    private static final int MAX_RETRIES = 3;

    private final String modelKey;
    private final String embeddingsUrl;
    private final String apiKey;
    private final String modelName;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 模型真实输出维度:0 表示未知,首次调用时按服务端返回确定并锁定。
     *
     * <p>不再由全局 `vector-dim` 指定:维度是模型的属性而不是平台配置,
     * 假定错了要么把可用模型判成不可用,要么写进维度不符的向量集合。</p>
     */
    private volatile int dim;

    /**
     * 维度探测用的固定短文本(计入一次最小 token 消耗,结果丢弃)
     */
    private static final String DIM_PROBE_TEXT = "dimension-probe";

    public OpenAiEmbeddingService(String modelKey, String baseUrl, String apiKey, String modelName, int configuredDim) {
        this.modelKey = modelKey;
        String normalized = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.embeddingsUrl = normalized.endsWith("/v1") ? normalized + "/embeddings" : normalized + "/v1/embeddings";
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dim = Math.max(configuredDim, 0);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String modelKey() {
        return modelKey;
    }

    @Override
    public int dimension() {
        return dim > 0 ? dim : -1;
    }

    @Override
    public int warmUp() {
        if (dim > 0) {
            return dim;
        }
        try {
            callEmbeddings(List.of(DIM_PROBE_TEXT));
            log.info("[嵌入] 模型 {} 真实维度探测为 {}", modelKey, dim);
            return dimension();
        } catch (Exception e) {
            log.warn("[嵌入] 模型 {} 维度探测失败,将回退默认维度: {}", modelKey, e.getMessage());
            return -1;
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()));
            all.addAll(embedBatch(batch));
        }
        return all;
    }

    /**
     * 单批嵌入,网络类失败按 1s/2s 退避重试;配置类错误(维度/数量不一致)立即失败
     */
    private List<float[]> embedBatch(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callEmbeddings(batch);
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new ServiceException("调用嵌入模型失败:" + e.getMessage());
                log.warn("[嵌入] 第 {}/{} 次调用失败: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    private List<float[]> callEmbeddings(List<String> texts) {
        try {
            String body = objectMapper.writeValueAsString(new EmbeddingRequest(modelName, texts));
            String resp = restClient.post()
                .uri(embeddingsUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode root = objectMapper.readTree(resp);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new ServiceException("嵌入模型返回数量与输入不一致");
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (JsonNode node : data) {
                JsonNode emb = node.path("embedding");
                int size = emb.isArray() ? emb.size() : 0;
                if (size <= 0) {
                    throw new ServiceException("嵌入模型返回空向量,请检查模型配置");
                }
                if (dim <= 0) {
                    // 首次调用确定维度:以服务端返回为准
                    synchronized (this) {
                        if (dim <= 0) {
                            dim = size;
                        }
                    }
                } else if (size != dim) {
                    throw new ServiceException("嵌入模型返回维度不一致(" + size + " vs 已确定 " + dim
                        + "),请检查模型配置或混用了不同模型");
                }
                float[] vec = new float[size];
                for (int i = 0; i < size; i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                vectors.add(vec);
            }
            return vectors;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("调用嵌入模型失败:" + e.getMessage());
        }
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }
}
