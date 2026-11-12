package com.forgeagent.rag.service.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.kg.service.KgText;
import com.forgeagent.kg.service.KgTriple;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKbKgEntity;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKbKgEntityMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.rag.service.eval.KbDocumentGenerationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R5-3 摄取侧实体/关系抽取:文档换代/删除事件后异步抽取,写入 forge-kg + 分块实体映射。
 *
 * <p><b>成本控制</b>:1 个分块 1 次模型调用;默认关闭({@code kg.extract-enabled=false}),
 * {@code kg.extract-model} 必须配成 agent_model 里可用的对话模型名,否则跳过并只告警一次;
 * 单文档抽取分块数受 {@code kg.extract-max-chunks}(默认 60)约束。</p>
 *
 * <p>失败了不影响主链路(与 M1 记忆抽取同口径):图是增强项,烂在图里比烂在库里好。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class KbKgExtractService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROMPT = """
        从下面的知识库片段中抽取实体与关系。只输出 JSON 数组,不要输出任何其他文字。
        格式:[{"s":"主语实体","r":"关系","o":"宾语实体"}],最多 8 条。
        规则:实体用文中出现的原词(不缩写、不改写);关系用 2~6 个字的中文动词短语(如"依赖""属于""包含");
        没有明确关系就输出 []。片段:
        """;

    private final KgService kgService;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final AgentKbKgEntityMapper entityMapper;
    private final AgentModelMapper modelMapper;
    private final KnowledgeProperties properties;

    /**
     * 一轮模型调用失败的告警去重标记(避免每篇文档刷屏)
     */
    private volatile String lastWarn = "";

    @Async
    @EventListener
    public void onDocumentGeneration(KbDocumentGenerationEvent event) {
        if (!Boolean.TRUE.equals(properties.getKgExtractEnabled()) || !kgService.enabled()) {
            return;
        }
        try {
            extract(event.knowledgeId(), event.documentId());
        } catch (Exception e) {
            log.warn("[知识库] 文档 {} 图谱抽取失败(不影响检索): {}", event.documentId(), e.getMessage());
        }
    }

    private void extract(Long knowledgeId, Long documentId) {
        AgentKnowledge kb = knowledgeMapper.selectById(knowledgeId);
        AgentKnowledgeDocument doc = documentMapper.selectById(documentId);
        if (kb == null || doc == null) {
            // 文档被删除:清掉该文档的映射行(图节点保留,由治理侧按需清理)
            entityMapper.delete(new LambdaQueryWrapper<AgentKbKgEntity>()
                .eq(AgentKbKgEntity::getDocumentId, documentId));
            return;
        }
        AgentModel model = resolveModel();
        if (model == null) {
            warnOnce("未配置可用的抽取模型(kg.extract-model),跳过图谱抽取");
            return;
        }
        int maxChunks = Math.max(1, properties.getKgExtractMaxChunks());
        List<AgentKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
            .eq(AgentKbChunk::getDocumentId, documentId)
            .eq(AgentKbChunk::getGeneration, doc.getActiveGeneration())
            .eq(AgentKbChunk::getChunkRole, "c")
            .eq(AgentKbChunk::getEnabled, 1)
            .orderByAsc(AgentKbChunk::getChunkIndex)
            .last("limit " + maxChunks));
        if (chunks.isEmpty()) {
            return;
        }
        KgScope scope = KgScope.workspace(kb.getWorkspaceId() == null ? 0L : kb.getWorkspaceId(), 0L);
        int extractedChunks = 0;
        for (AgentKbChunk chunk : chunks) {
            // 重建后同一分块可能换了内容,先清掉旧映射(按 chunkId 唯一,幂等覆盖)
            entityMapper.delete(new LambdaQueryWrapper<AgentKbKgEntity>()
                .eq(AgentKbKgEntity::getChunkId, chunk.getId()));
            String content = StringUtils.defaultString(chunk.getContent());
            if (content.length() < 20) {
                continue;
            }
            List<KgTriple> triples = callModel(model, content, chunk.getId());
            if (triples.isEmpty()) {
                continue;
            }
            kgService.ingest(scope, triples);
            Set<String> keys = new LinkedHashSet<>();
            for (KgTriple triple : triples) {
                insertEntity(knowledgeId, documentId, chunk.getId(), triple.subject(), keys);
                insertEntity(knowledgeId, documentId, chunk.getId(), triple.object(), keys);
            }
            extractedChunks++;
        }
        log.info("[知识库] 文档 {} 图谱抽取完成:{} 个分块产出三元组(上限 {})",
            documentId, extractedChunks, maxChunks);
    }

    private void insertEntity(Long knowledgeId, Long documentId, Long chunkId, String name, Set<String> keys) {
        String key = KgText.nameKey(name);
        if (key.isEmpty() || !keys.add(key)) {
            return;
        }
        AgentKbKgEntity entity = new AgentKbKgEntity();
        entity.setKnowledgeId(knowledgeId);
        entity.setDocumentId(documentId);
        entity.setChunkId(chunkId);
        entity.setNodeKey(key);
        entity.setNodeName(KgText.clean(name));
        entity.setCreateTime(new Date());
        entityMapper.insert(entity);
    }

    private List<KgTriple> callModel(AgentModel model, String content, Long chunkId) {
        try {
            Model asModel = AgentScopeModelFactory.build(model.getBaseUrl(), model.getApiKey(),
                model.getModelName(), 0.0, 800);
            ReActAgent agent = ReActAgent.builder().name("kb-kg-extract").model(asModel).maxIters(1).build();
            Msg reply = agent.call(Msg.builder().name("user").role(MsgRole.USER)
                    .textContent(PROMPT + content).build())
                .block(Duration.ofSeconds(45));
            String text = reply == null ? null : reply.getTextContent();
            if (StringUtils.isBlank(text)) {
                return List.of();
            }
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return List.of();
            }
            JsonNode arr = MAPPER.readTree(text.substring(start, end + 1));
            List<KgTriple> triples = new ArrayList<>();
            for (JsonNode node : arr) {
                String s = node.path("s").asText("");
                String r = node.path("r").asText("");
                String o = node.path("o").asText("");
                if (StringUtils.isBlank(s) || StringUtils.isBlank(r) || StringUtils.isBlank(o)) {
                    continue;
                }
                triples.add(new KgTriple(s, r, o, chunkId));
                if (triples.size() >= 8) {
                    break;
                }
            }
            return triples;
        } catch (Exception e) {
            warnOnce("图谱抽取模型调用失败:" + e.getMessage());
            return List.of();
        }
    }

    private AgentModel resolveModel() {
        String name = properties.getKgExtractModel();
        if (StringUtils.isBlank(name)) {
            return null;
        }
        List<AgentModel> models = modelMapper.selectList(new LambdaQueryWrapper<AgentModel>()
            .eq(AgentModel::getModelName, name.trim())
            .last("limit 1"));
        if (models.isEmpty()) {
            return null;
        }
        AgentModel model = models.getFirst();
        return StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getApiKey())
            ? null : model;
    }

    private void warnOnce(String message) {
        if (!message.equals(lastWarn)) {
            lastWarn = message;
            log.warn("[知识库] {}", message);
        }
    }
}
