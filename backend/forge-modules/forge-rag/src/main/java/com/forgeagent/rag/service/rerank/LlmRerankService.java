package com.forgeagent.rag.service.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.service.AgentScopeModelFactory;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * 基于大模型的检索结果重排序实现:让模型对每个候选片段与查询的相关性打 0-100 分,
 * 再按分数降序重排。复用 AgentScope 内核与平台模型配置,无需单独的 rerank 服务端点。
 *
 * @author ForgeAgent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRerankService implements RerankService {

    private final AgentModelMapper modelMapper;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从模型回复中提取 JSON 数组(容忍前后包裹说明文字)
     */
    private static final Pattern ARRAY_PATTERN = Pattern.compile("\\[[^\\[\\]]*]");

    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(30);

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
        String prompt = buildPrompt(query, candidates);
        Model asModel = AgentScopeModelFactory.build(
            model.getBaseUrl(), model.getApiKey(), model.getModelName(), 0.0, 1024);
        ReActAgent agent = ReActAgent.builder()
            .name("rerank")
            .model(asModel)
            .maxIters(1)
            .build();
        Msg reply = agent.call(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(prompt)
                .build())
            .block(RERANK_TIMEOUT);
        String text = reply == null ? null : reply.getTextContent();
        double[] scores = parseScores(text, candidates.size());
        if (scores == null) {
            throw new ServiceException("重排序模型返回结果无法解析");
        }
        List<RerankItem> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            // 模型输出 0-100 分,归一化到 0-1 区间,与向量相似度/阈值口径一致
            items.add(new RerankItem(i, scores[i] / 100.0));
        }
        items.sort(Comparator.comparingDouble(RerankItem::score).reversed());
        return items;
    }

    /**
     * 构造打分提示词:编号列出候选片段,要求模型只输出等长分数数组
     */
    private String buildPrompt(String query, List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个检索结果重排序助手。请判断下列每个候选片段与用户问题的相关性,")
            .append("输出 0-100 的整数分数(越大越相关)。\n");
        sb.append("用户问题:").append(query).append('\n');
        sb.append("候选片段:\n");
        for (int i = 0; i < candidates.size(); i++) {
            String content = candidates.get(i);
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500);
            }
            sb.append('[').append(i).append("] ").append(content == null ? "" : content).append('\n');
        }
        sb.append("只输出一个 JSON 数组,长度必须为 ").append(candidates.size())
            .append(",第 n 个元素是序号 n 片段的相关性分数,例如:[85,20,60]。不要输出任何其他内容。");
        return sb.toString();
    }

    /**
     * 解析分数数组;数量不足或解析失败返回 null
     */
    private double[] parseScores(String text, int expected) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Matcher matcher = ARRAY_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                JsonNode node = MAPPER.readTree(matcher.group());
                if (!node.isArray() || node.size() != expected) {
                    continue;
                }
                double[] scores = new double[expected];
                for (int i = 0; i < expected; i++) {
                    scores[i] = node.path(i).asDouble(0);
                }
                return scores;
            } catch (Exception ignore) {
                // 尝试下一个数组片段
            }
        }
        return null;
    }
}
