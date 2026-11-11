package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agentcore.utils.ChatTexts;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKbQueryLog;
import com.forgeagent.rag.domain.AgentKbZeroHitLog;
import com.forgeagent.rag.service.retrieve.DegradedReason;
import com.forgeagent.rag.service.retrieve.ParentRedactor;
import com.forgeagent.rag.service.retrieve.SearchOutcome;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.AgentKnowledgeDocument;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.rag.domain.bo.AgentKnowledgeBo;
import com.forgeagent.rag.domain.bo.KbQueryLogBo;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.AgentKbQueryLogVo;
import com.forgeagent.rag.domain.vo.AgentKbZeroHitLogVo;
import com.forgeagent.rag.domain.vo.AgentKnowledgeVo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.domain.vo.KbRebuildProgressVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKbZeroHitLogMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeDocumentMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.rag.service.KnowledgeDocProcessor;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.rag.service.embedding.EmbeddingFactory;
import com.forgeagent.rag.service.embedding.EmbeddingService;
import com.forgeagent.rag.service.rerank.ApiRerankService;
import com.forgeagent.rag.service.rerank.LlmRerankService;
import com.forgeagent.rag.service.rerank.RerankService;
import com.forgeagent.rag.service.retrieve.ScoreNormalizer;
import com.forgeagent.rag.service.retrieve.ScoreThresholdPolicy;
import com.forgeagent.rag.service.vector.VectorStore;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
/**
 * 知识库注册 Service 实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentKnowledgeServiceImpl implements IAgentKnowledgeService {

    /**
     * LLM 打分重排的最大候选数(按候选拼接 prompt,超量截断以控制延迟与 token 成本)
     */
    private static final int LLM_RERANK_MAX_CANDIDATES = 20;

    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentKnowledgeDocumentMapper documentMapper;
    private final AgentKbChunkMapper chunkMapper;
    private final AgentKbZeroHitLogMapper zeroHitLogMapper;
    private final com.forgeagent.rag.mapper.AgentKbQueryLogMapper queryLogMapper;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentModelMapper modelMapper;
    private final AgentWorkspaceMemberMapper workspaceMemberMapper;
    private final EmbeddingFactory embeddingFactory;
    private final VectorStore vectorStore;
    private final LlmRerankService llmRerankService;
    private final ApiRerankService apiRerankService;
    private final KnowledgeProperties knowledgeProperties;
    private final AuditLogHelper auditLogHelper;
    private final WorkspacePermissionHelper permissionHelper;
    private final KnowledgeDocProcessor docProcessor;

    /**
     * 知识库统计类异步执行器(命中计数/零命中日志)
     */
    @jakarta.annotation.Resource(name = "kbHitCountExecutor")
    private java.util.concurrent.ExecutorService hitCountWorker;

    /**
     * 精排专用线程池(队列为 0:排不进去立刻拒绝,不让请求白等超时)
     */
    @jakarta.annotation.Resource(name = "kbRerankExecutor")
    private java.util.concurrent.ExecutorService rerankExecutor;

    @Override
    public PageResult<AgentKnowledgeVo> queryPageList(AgentKnowledgeBo bo, PageQuery pageQuery) {
        List<Long> scopeWorkspaceIds = resolveScope(bo.getWorkspaceId());
        LambdaQueryWrapper<AgentKnowledge> wrapper = new LambdaQueryWrapper<AgentKnowledge>()
            .eq(AgentKnowledge::getKbScope, "normal");
        // 空间可见性:超管看全部;普通用户看全局(workspace_id IS NULL)+ 其所属空间;指定空间时叠加该空间
        if (scopeWorkspaceIds == null) {
            // 超管或已校验成员的指定空间:指定空间时限定,否则不限定
            if (bo.getWorkspaceId() != null) {
                wrapper.and(w -> w.eq(AgentKnowledge::getWorkspaceId, bo.getWorkspaceId())
                    .or().isNull(AgentKnowledge::getWorkspaceId));
            }
        } else {
            wrapper.and(w -> w.isNull(AgentKnowledge::getWorkspaceId)
                .or().in(AgentKnowledge::getWorkspaceId, scopeWorkspaceIds));
        }
        wrapper.like(StringUtils.isNotBlank(bo.getKeyword()), AgentKnowledge::getName, bo.getKeyword())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentKnowledge::getStatus, bo.getStatus())
            .orderByDesc(AgentKnowledge::getId);
        Page<AgentKnowledgeVo> page = knowledgeMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentKnowledgeVo queryById(Long id) {
        AgentKnowledge entity = knowledgeMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsReadable(entity.getWorkspaceId());
        return MapstructUtils.convert(entity, AgentKnowledgeVo.class);
    }

    @Override
    public Boolean insertByBo(AgentKnowledgeBo bo) {
        // 工作空间归属:未指定时仅超管可创建全局知识库
        Long workspaceId = bo.getWorkspaceId();
        if (workspaceId == null) {
            if (!LoginHelper.isSuperAdmin()) {
                throw new ServiceException("请指定所属工作空间");
            }
        } else {
            permissionHelper.checkWsDeveloper(workspaceId);
        }
        checkNameUnique(bo.getName(), null, workspaceId);
        AgentKnowledge entity = MapstructUtils.convert(bo, AgentKnowledge.class);
        entity.setStatus("0");
        entity.setKbScope("normal");
        entity.setVectorBackend("milvus");
        entity.setWorkspaceId(workspaceId);
        if (StringUtils.isBlank(entity.getEmbeddingModel())) {
            entity.setEmbeddingModel("local-hash");
        }
        // 分块口径:单位决定"chunkSize 这个数字是什么意思",策略决定要不要做结构保护
        String unit = normalizeChunkUnit(bo.getChunkUnit());
        entity.setChunkUnit(unit);
        entity.setSplitStrategy(normalizeSplitStrategy(bo.getSplitStrategy()));
        boolean tokenUnit = com.forgeagent.rag.service.document.TextChunker.UNIT_TOKEN.equals(unit);
        if (entity.getChunkSize() == null) {
            entity.setChunkSize(tokenUnit ? 400 : 500);
        }
        if (entity.getChunkOverlap() == null) {
            entity.setChunkOverlap(tokenUnit ? 60 : 50);
        }
        entity.setDocCount(0);
        // 记录向量维度(外部模型以真实返回为准,local-hash 取全局配置),用于集合分片与重建校验
        entity.setVectorDim(embeddingFactory.resolveDimension(entity));
        boolean ok = knowledgeMapper.insert(entity) > 0;
        if (ok) {
            auditLogHelper.record(workspaceId, "kb.create", "knowledge", String.valueOf(entity.getId()),
                "创建知识库[" + entity.getName() + "]",
                null,
                Map.of("name", entity.getName(), "embeddingModel", entity.getEmbeddingModel(),
                    "chunkSize", entity.getChunkSize(), "rerankModel", StringUtils.defaultString(entity.getRerankModel(), "")),
                "0", "WARN");
        }
        return ok;
    }

    @Override
    public Boolean updateByBo(AgentKnowledgeBo bo) {
        AgentKnowledge old = knowledgeMapper.selectById(bo.getId());
        if (old == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsWritable(old.getWorkspaceId());
        checkNameUnique(bo.getName(), bo.getId(), old.getWorkspaceId());
        // 嵌入模型变更:向量库按维度分集合,维度不同也能共存,因此不再一刀切拒绝切换;
        // 但旧向量在新模型的向量空间里没有意义,必须重建索引,这里只负责把新维度记录下来并留痕
        boolean embeddingChanged = !StringUtils.equals(old.getEmbeddingModel(), bo.getEmbeddingModel())
            && StringUtils.isNotBlank(bo.getEmbeddingModel());
        Integer newDim = null;
        if (embeddingChanged) {
            AgentKnowledge probe = new AgentKnowledge();
            probe.setEmbeddingModel(bo.getEmbeddingModel());
            newDim = embeddingFactory.resolveDimension(probe);
            if (old.getVectorDim() != null && !old.getVectorDim().equals(newDim)) {
                log.warn("[知识库] {} 嵌入模型维度 {} → {},存量向量需重建索引后才会被检索到",
                    old.getName(), old.getVectorDim(), newDim);
            }
        }
        AgentKnowledge update = MapstructUtils.convert(bo, AgentKnowledge.class);
        update.setStatus(null);
        update.setVectorBackend(null);
        update.setVectorDim(null);
        update.setDocCount(null);
        update.setWorkspaceId(null);
        // 分块口径:留空即不改(updateById 忽略 null),非法值直接拒绝,避免存进一个解析器不认识的枚举
        update.setChunkUnit(StringUtils.isBlank(bo.getChunkUnit()) ? null : normalizeChunkUnit(bo.getChunkUnit()));
        update.setSplitStrategy(StringUtils.isBlank(bo.getSplitStrategy())
            ? null : normalizeSplitStrategy(bo.getSplitStrategy()));
        boolean ok = knowledgeMapper.updateById(update) > 0;
        // updateById 忽略 null 字段,相关度阈值与向量维度单独显式写入以支持清空/更新
        var configUpdate = com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentKnowledge>lambdaUpdate()
            .eq(AgentKnowledge::getId, bo.getId())
            .set(AgentKnowledge::getScoreThreshold, bo.getScoreThreshold())
            .set(AgentKnowledge::getRerankThreshold, bo.getRerankThreshold());
        if (newDim != null) {
            configUpdate.set(AgentKnowledge::getVectorDim, newDim);
        }
        knowledgeMapper.update(null, configUpdate);
        if (ok) {
            boolean rerankChanged = !Objects.equals(
                StringUtils.defaultString(old.getRerankModel(), ""),
                StringUtils.defaultString(bo.getRerankModel(), ""));
            String eventType = rerankChanged ? "kb.rerank.config"
                : (embeddingChanged ? "kb.embedding.config" : "kb.update");
            String severity = rerankChanged || embeddingChanged ? "WARN" : "INFO";
            String detail;
            if (embeddingChanged) {
                detail = "知识库[" + old.getName() + "]嵌入模型变更:"
                    + StringUtils.defaultString(old.getEmbeddingModel(), "无") + " → " + bo.getEmbeddingModel()
                    + "(维度 " + old.getVectorDim() + " → " + newDim + ",须重建索引)";
            } else if (rerankChanged) {
                detail = "知识库[" + old.getName() + "]重排模型变更:"
                    + StringUtils.defaultString(old.getRerankModel(), "无")
                    + " → " + StringUtils.defaultString(bo.getRerankModel(), "无");
            } else {
                detail = "更新知识库[" + old.getName() + "]配置";
            }
            auditLogHelper.record(old.getWorkspaceId(), eventType, "knowledge", String.valueOf(bo.getId()),
                detail,
                Map.of("name", old.getName(), "rerankModel", StringUtils.defaultString(old.getRerankModel(), ""),
                    "embeddingModel", StringUtils.defaultString(old.getEmbeddingModel(), ""),
                    "vectorDim", String.valueOf(old.getVectorDim())),
                Map.of("name", bo.getName(), "rerankModel", StringUtils.defaultString(bo.getRerankModel(), ""),
                    "embeddingModel", StringUtils.defaultString(bo.getEmbeddingModel(), ""),
                    "vectorDim", String.valueOf(newDim)),
                "0", severity);
        }
        return ok;
    }

    @Override
    public Boolean changeStatus(Long id, String status) {
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException("非法的知识库状态");
        }
        AgentKnowledge kb = knowledgeMapper.selectById(id);
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsWritable(kb.getWorkspaceId());
        AgentKnowledge update = new AgentKnowledge();
        update.setId(id);
        update.setStatus(status);
        return knowledgeMapper.updateById(update) > 0;
    }

    @Override
    public Boolean rebuildIndex(Long id) {
        AgentKnowledge kb = knowledgeMapper.selectById(id);
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsWritable(kb.getWorkspaceId());
        // 1. 刷新向量维度记录为当前嵌入模型实际维度(外部模型以真实返回为准)
        AgentKnowledge dimUpdate = new AgentKnowledge();
        dimUpdate.setId(id);
        dimUpdate.setVectorDim(embeddingFactory.resolveDimension(kb));
        knowledgeMapper.updateById(dimUpdate);
        // 2. 逐文档按「新代次」重建:新代写成功后才切指针,期间旧代照常可检索。
        //    不再先删后建——旧实现重建期间该库检索恒为空,中途失败则库彻底不可用
        List<AgentKnowledgeDocument> docs = documentMapper.selectList(
            new LambdaQueryWrapper<AgentKnowledgeDocument>().eq(AgentKnowledgeDocument::getKnowledgeId, id));
        for (AgentKnowledgeDocument doc : docs) {
            int active = doc.getActiveGeneration() == null ? 1 : doc.getActiveGeneration();
            documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getId, doc.getId())
                .set(AgentKnowledgeDocument::getStatus, "0")
                .set(AgentKnowledgeDocument::getFailReason, null));
            docProcessor.processGenerationAsync(doc.getId(), active + 1);
        }
        auditLogHelper.record(kb.getWorkspaceId(), "kb.reindex", "knowledge", String.valueOf(id),
            "重建知识库[" + kb.getName() + "]索引(共 " + docs.size() + " 个文档,按代次重建,重建期间旧索引可用)",
            null,
            Map.of("name", kb.getName(), "docCount", docs.size()),
            "0", "WARN");
        return true;
    }

    @Override
    public KbRebuildProgressVo rebuildProgress(Long id) {
        AgentKnowledge kb = knowledgeMapper.selectById(id);
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsReadable(kb.getWorkspaceId());
        LambdaQueryWrapper<AgentKnowledgeDocument> scope = new LambdaQueryWrapper<AgentKnowledgeDocument>()
            .eq(AgentKnowledgeDocument::getKnowledgeId, id);
        long total = documentMapper.selectCount(scope);
        long success = documentMapper.selectCount(scope.clone().eq(AgentKnowledgeDocument::getStatus, "1"));
        long failed = documentMapper.selectCount(scope.clone().eq(AgentKnowledgeDocument::getStatus, "2"));
        long pending = total - success - failed;
        KbRebuildProgressVo vo = new KbRebuildProgressVo();
        vo.setKnowledgeId(id);
        vo.setTotal(total);
        vo.setPending(Math.max(pending, 0));
        vo.setSuccess(success);
        vo.setFailed(failed);
        vo.setPercent(total == 0 ? 100 : (int) Math.round((success + failed) * 100.0 / total));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentKnowledge kb = knowledgeMapper.selectById(id);
            if (kb == null) {
                continue;
            }
            // 删除知识库前列出绑定的 Agent 并要求先解绑(FR-M4-03)
            checkWsWritable(kb.getWorkspaceId());
            List<String> boundAgents = findBoundAgents(id);
            if (!boundAgents.isEmpty()) {
                throw new ServiceException("知识库「" + kb.getName() + "」已绑定 Agent:" + String.join("、", boundAgents) + ",请先解绑");
            }
            // 级联删除:向量 -> 分块 -> 文档与文件 -> 知识库
            try {
                vectorStore.deleteByKnowledgeId(id);
            } catch (Exception e) {
                log.warn("[知识库] 删除知识库 {} 向量失败: {}", id, e.getMessage());
            }
            chunkMapper.delete(new LambdaQueryWrapper<AgentKbChunk>().eq(AgentKbChunk::getKnowledgeId, id));
            List<AgentKnowledgeDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<AgentKnowledgeDocument>().eq(AgentKnowledgeDocument::getKnowledgeId, id));
            for (AgentKnowledgeDocument doc : docs) {
                try {
                    Files.deleteIfExists(Path.of(doc.getFilePath()));
                } catch (Exception e) {
                    log.warn("[知识库] 删除文档文件失败: {}", e.getMessage());
                }
            }
            documentMapper.delete(new LambdaQueryWrapper<AgentKnowledgeDocument>()
                .eq(AgentKnowledgeDocument::getKnowledgeId, id));
            knowledgeMapper.deleteById(id);
            auditLogHelper.record(kb.getWorkspaceId(), "kb.delete", "knowledge", String.valueOf(id),
                "删除知识库[" + kb.getName() + "],级联删除 " + docs.size() + " 篇文档",
                Map.of("name", kb.getName(), "docCount", docs.size(),
                    "rerankModel", StringUtils.defaultString(kb.getRerankModel(), "")),
                null, "0", "RISK");
        }
        return true;
    }

    @Override
    public List<KnowledgeSearchHitVo> search(KnowledgeSearchBo bo) {
        AgentKnowledge kb = knowledgeMapper.selectById(bo.getKnowledgeId());
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsReadable(kb.getWorkspaceId());
        return doSearch(bo, kb).hits();
    }

    @Override
    public SearchOutcome searchOutcome(KnowledgeSearchBo bo) {
        AgentKnowledge kb = knowledgeMapper.selectById(bo.getKnowledgeId());
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        checkWsReadable(kb.getWorkspaceId());
        return doSearch(bo, kb);
    }

    @Override
    public List<KnowledgeSearchHitVo> searchInternal(KnowledgeSearchBo bo) {
        AgentKnowledge kb = knowledgeMapper.selectById(bo.getKnowledgeId());
        if (kb == null) {
            throw new ServiceException("知识库不存在");
        }
        // 体检 S2:内部入口不做登录态校验,但必须做工作空间归属校验——
        // 目标库须为全局库或属于调用方(工作流归属 Agent)的空间,堵住跨空间知识外泄
        Long callerWs = bo.getCallerWorkspaceId();
        if (kb.getWorkspaceId() != null
            && (callerWs == null || !callerWs.equals(kb.getWorkspaceId()))) {
            throw new ServiceException("知识库不存在或无权访问");
        }
        return doSearch(bo, kb).hits();
    }

    /**
     * 检索主体(search/searchInternal 共用):权限校验已在入口完成。
     *
     * <p>返回回执而非裸列表:调用方需要知道"这次走成什么样"(有没有降级、阈值作用在哪种分、
     * 各路召回多少条),否则结果异常时只能翻服务端日志猜。</p>
     */
    private SearchOutcome doSearch(KnowledgeSearchBo bo, AgentKnowledge kb) {
        try {
            return searchOnce(bo, kb);
        } catch (GenerationRaceException e) {
            // 切代竞态:分块读到旧代、指针读到新代。整次重跑一次即可自愈;
            // 必须绕过缓存,否则拿回的仍是同一份已失效的结果
            log.debug("[知识库] {} 命中切代窗口,重新检索一次", kb.getId());
            bo.setCacheable(Boolean.FALSE);
            return searchOnce(bo, kb);
        }
    }

    /**
     * 检索主体(单次执行):权限校验已在入口完成。
     *
     * <p>返回回执而非裸列表:调用方需要知道"这次走成什么样"(有没有降级、阈值作用在哪种分、
     * 各路召回多少条),否则结果异常时只能翻服务端日志猜。</p>
     */
    private SearchOutcome searchOnce(KnowledgeSearchBo bo, AgentKnowledge kb) {
        long startedAt = System.currentTimeMillis();
        if (!"0".equals(kb.getStatus())) {
            throw new ServiceException("知识库已停用");
        }
        List<DegradedReason> degraded = new ArrayList<>();
        int topK = bo.getTopK() == null ? 5 : bo.getTopK();
        String mode = StringUtils.isBlank(bo.getSearchMode()) ? "hybrid" : bo.getSearchMode().toLowerCase();
        // 开启精排时先放大召回:粗排多召回若干候选,再由重排序模型打分取最终 topK
        boolean rerank = Boolean.TRUE.equals(bo.getRerank());
        int rerankLimit = knowledgeProperties.getRerankCandidateLimit() == null
            ? 50 : Math.max(knowledgeProperties.getRerankCandidateLimit(), topK);
        int recall = rerank ? Math.min(Math.max(topK * 4, 20), rerankLimit) : topK;

        // 对话链路结果缓存(默认 60s):高频重复提问省一次 embedding+rerank 调用;
        // 命中测试 cacheable=false 不走缓存,保证分块编辑/禁用后实时可见
        String cacheKey = null;
        if (Boolean.TRUE.equals(bo.getCacheable()) && Boolean.TRUE.equals(knowledgeProperties.getRagCacheEnabled())) {
            cacheKey = "agent:kb:search:" + kb.getId() + ":" + org.springframework.util.DigestUtils.md5DigestAsHex(
                (bo.getQuery() + '|' + topK + '|' + mode + '|' + rerank + '|' + kb.getScoreThreshold()
                    + '|' + kb.getRerankThreshold())
                    .getBytes(StandardCharsets.UTF_8));
            try {
                List<KnowledgeSearchHitVo> cached = RedisUtils.getCacheObject(cacheKey);
                if (cached != null) {
                    // 缓存命中不重复累计分块命中次数
                    return new SearchOutcome(cached, degraded, null, 0, 0,
                        System.currentTimeMillis() - startedAt);
                }
            } catch (Exception e) {
                // Redis 不可用不能把整次检索打挂:降级直查,并留下降级痕迹
                degraded.add(DegradedReason.CACHE_BYPASS);
                log.warn("[知识库] 检索缓存不可用,本次直查: {}", e.getMessage());
            }
        }

        // 向量召回(语义相似);放大召回补偿禁用分块过滤造成的截断
        // 向量库不可用时降级为关键词召回,不阻断检索
        List<VectorStore.VectorHit> vectorHits = List.of();
        if ("vector".equals(mode) || "hybrid".equals(mode)) {
            try {
                EmbeddingService embedding = embeddingFactory.forKnowledge(kb);
                float[] queryVector = embedding.embed(List.of(bo.getQuery())).get(0);
                vectorHits = vectorStore.search(kb.getId(), queryVector, Math.min(recall * 2, 100));
            } catch (Exception e) {
                degraded.add(DegradedReason.VECTOR_ROUTE_UNAVAILABLE);
                log.warn("[知识库] 向量召回失败,降级为关键词召回: {}", e.getMessage());
                if ("vector".equals(mode)) {
                    mode = "keyword";
                }
            }
        }

        // 关键词召回(全文索引精确匹配,SQL 层已过滤禁用分块)
        List<Map<String, Object>> keywordHits = List.of();
        if ("keyword".equals(mode) || "hybrid".equals(mode)) {
            try {
                keywordHits = chunkMapper.searchByKeyword(kb.getId(), bo.getQuery(), recall);
            } catch (Exception e) {
                degraded.add(DegradedReason.KEYWORD_ROUTE_FAILED);
                log.warn("[知识库] 关键词召回失败,本次仅用向量路: {}", e.getMessage());
            }
        }
        int vectorHitCount = vectorHits.size();
        int keywordHitCount = keywordHits.size();

        // 收集命中分块,剔除已禁用分块(禁用不参与检索)
        List<Long> allChunkIds = new ArrayList<>();
        vectorHits.forEach(h -> allChunkIds.add(h.id()));
        keywordHits.forEach(h -> allChunkIds.add(((Number) h.get("id")).longValue()));
        if (allChunkIds.isEmpty()) {
            return new SearchOutcome(List.of(), degraded, null, vectorHitCount, keywordHitCount,
                System.currentTimeMillis() - startedAt);
        }
        ChunkAndDoc loaded = loadChunksAndDocs(allChunkIds);
        if (loaded.chunks().isEmpty() && loaded.rawCount() > 0) {
            // 有分块但全被判为过期 = 读到了「旧代分块 + 新代指针」的中间态,
            // 交给上层整次重试(新代分块是另一批 id,重读同一批 id 救不回来)
            throw new GenerationRaceException();
        }
        final Map<Long, AgentKbChunk> chunkMap = loaded.chunks();
        final Map<Long, AgentKnowledgeDocument> docMap = loaded.docs();
        vectorHits = vectorHits.stream().filter(h -> chunkMap.containsKey(h.id())).toList();
        keywordHits = keywordHits.stream()
            .filter(h -> chunkMap.containsKey(((Number) h.get("id")).longValue())).toList();
        if (chunkMap.isEmpty()) {
            return new SearchOutcome(List.of(), degraded, null, vectorHitCount, keywordHitCount,
                System.currentTimeMillis() - startedAt);
        }
        Map<Long, String> docNameMap = docMap.values().stream()
            .collect(Collectors.toMap(AgentKnowledgeDocument::getId, AgentKnowledgeDocument::getFileName));

        // 父子分块:命中的子块回填父块全文,作为对话注入的完整上下文(small-to-big)
        boolean parentMode = "1".equals(kb.getParentChunkEnabled());
        Map<Long, String> parentContentLookup = Map.of();
        if (parentMode) {
            List<Long> parentIds = chunkMap.values().stream()
                .filter(c -> "c".equals(c.getChunkRole()) && c.getParentId() != null)
                .map(AgentKbChunk::getParentId).distinct().toList();
            if (!parentIds.isEmpty()) {
                parentContentLookup = chunkMapper.selectByIds(parentIds).stream()
                    .collect(Collectors.toMap(AgentKbChunk::getId,
                        c -> com.forgeagent.rag.service.document.TokenEstimator.estimate(c.getContent()) > 0
                            ? c.getContent() : StringUtils.defaultString(c.getContent())));
                if (parentContentLookup.size() < parentIds.size()) {
                    // 父块被删/被重建换代会走到这里:命中子块仍可用,但注入的是子块原文而非父块全文
                    degraded.add(DegradedReason.PARENT_MISSING);
                }
            }
        }
        final Map<Long, String> parentContentMap = parentContentLookup;

        // 父块里混有已禁用子块时的处理:默认整块不返回(合规优先),
        // 库级开关为 0 时返回父块但挖除被禁用片段(偏移缺失则退化为整块不返回)
        boolean hideParentWithDisabledChild = !"0".equals(kb.getHideParentWithDisabledChild());
        Set<Long> parentsWithDisabledChild = new HashSet<>();
        Map<Long, List<int[]>> disabledRangesByParent = new HashMap<>();
        if (parentMode && !parentContentLookup.isEmpty()) {
            List<AgentKbChunk> disabledChildren = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
                .select(AgentKbChunk::getId, AgentKbChunk::getParentId,
                    AgentKbChunk::getParentOffsetStart, AgentKbChunk::getParentOffsetEnd)
                .in(AgentKbChunk::getParentId, parentContentLookup.keySet())
                .eq(AgentKbChunk::getEnabled, 0));
            for (AgentKbChunk child : disabledChildren) {
                parentsWithDisabledChild.add(child.getParentId());
                if (child.getParentOffsetStart() != null && child.getParentOffsetEnd() != null) {
                    disabledRangesByParent.computeIfAbsent(child.getParentId(), x -> new ArrayList<>())
                        .add(new int[]{child.getParentOffsetStart(), child.getParentOffsetEnd()});
                }
            }
        }

        // RRF 融合: score = Σ 1/(k + rank), k=60
        final int k = 60;
        Map<Long, Double> rrfScore = new HashMap<>();
        Map<Long, Double> vectorScoreMap = new HashMap<>();
        Map<Long, Double> keywordScoreMap = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            VectorStore.VectorHit hit = vectorHits.get(i);
            rrfScore.merge(hit.id(), 1.0 / (k + i + 1), Double::sum);
            vectorScoreMap.put(hit.id(), hit.score() == null ? null : hit.score().doubleValue());
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            Long cid = ((Number) keywordHits.get(i).get("id")).longValue();
            rrfScore.merge(cid, 1.0 / (k + i + 1), Double::sum);
            Object kw = keywordHits.get(i).get("score");
            keywordScoreMap.put(cid, kw == null ? null : ((Number) kw).doubleValue());
        }

        // 粗排候选集:RRF 降序,精排时保留更多候选用于二次打分
        // 关键词分按本次候选集 min-max 归一,供前端并排比较(Milvus 余弦与 BM25 原始分不可直接比)
        double kwMin = keywordScoreMap.values().stream().filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue).min().orElse(0);
        double kwMax = keywordScoreMap.values().stream().filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue).max().orElse(0);
        double kwSpread = kwMax - kwMin;
        List<KnowledgeSearchHitVo> candidates = rrfScore.entrySet().stream()
            // 同分时按 chunkId 升序破平:HashMap 的迭代顺序不稳定,不破平会导致
            // 同一 query 两次执行返回不同名次,评测结果无法复现(基线对不上就是这个原因)
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(recall)
            .map(e -> {
                AgentKbChunk chunk = chunkMap.get(e.getKey());
                if (chunk == null) {
                    return null;
                }
                KnowledgeSearchHitVo vo = new KnowledgeSearchHitVo();
                vo.setChunkId(chunk.getId());
                vo.setDocumentId(chunk.getDocumentId());
                vo.setFileName(docNameMap.getOrDefault(chunk.getDocumentId(), "未知文档"));
                vo.setChunkIndex(chunk.getChunkIndex());
                vo.setContent(chunk.getContent());
                vo.setScore(e.getValue());
                vo.setRrfScore(e.getValue());
                vo.setVectorScore(vectorScoreMap.get(e.getKey()));
                vo.setKeywordScore(keywordScoreMap.get(e.getKey()));
                vo.setNormVectorScore(ScoreNormalizer.unitFromCosine(vectorScoreMap.get(e.getKey())));
                Double kw = keywordScoreMap.get(e.getKey());
                vo.setNormKeywordScore(kw == null ? null
                    : (kwSpread <= 1e-9 ? 1d : ScoreNormalizer.clamp01((kw - kwMin) / kwSpread)));
                vo.setMatchChannels(vectorScoreMap.containsKey(e.getKey())
                    ? (keywordScoreMap.containsKey(e.getKey()) ? "both" : "vector") : "keyword");
                vo.setEnabled(chunk.getEnabled());
                vo.setPageLabel(chunk.getPageLabel());
                // parentContentMap 是 Map.of() 构造的不可变 Map,对 null key 会抛 NPE;
                // 普通分块没有父块(parentId 为 null),必须先判空再取
                String parentContent = chunk.getParentId() == null
                    ? null : parentContentMap.get(chunk.getParentId());
                if (parentContent != null && parentsWithDisabledChild.contains(chunk.getParentId())) {
                    if (hideParentWithDisabledChild) {
                        // 父块里含禁用内容:整条命中丢弃,宁可少一条上下文也不把禁用内容喂给模型
                        return null;
                    }
                    String redacted = ParentRedactor.redact(parentContent,
                        disabledRangesByParent.get(chunk.getParentId()));
                    if (redacted == null) {
                        // 偏移失效:不做半吊子裁剪,同样整条丢弃
                        return null;
                    }
                    parentContent = redacted;
                }
                vo.setParentContent(parentContent);
                return vo;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(ArrayList::new));

        // 重排序精排:模型对候选打分后重排取最终 topK;失败/超时都回退 RRF 粗排,不阻断检索。
        // 路由:模型名含 rerank 走标准 Reranker API(一次调用全量打分),否则走 LLM 逐条打分;
        // LLM 打分按候选拼接 prompt,候选截断到 12 条控制延迟与成本(API 路径按全文打分不受限)
        if (rerank && !candidates.isEmpty()) {
            AgentModel rerankModel = resolveRerankModel(kb);
            if (rerankModel == null) {
                degraded.add(DegradedReason.RERANK_UNAVAILABLE);
                log.warn("[知识库] 已开启重排序但知识库未配置重排序模型,回退 RRF 粗排");
            } else {
                RerankService reranker = ApiRerankService.supports(rerankModel) ? apiRerankService : llmRerankService;
                List<KnowledgeSearchHitVo> scored = candidates;
                if (reranker == llmRerankService && candidates.size() > LLM_RERANK_MAX_CANDIDATES) {
                    scored = candidates.subList(0, LLM_RERANK_MAX_CANDIDATES);
                }
                List<String> texts = scored.stream().map(KnowledgeSearchHitVo::getContent).toList();
                try {
                    List<RerankService.RerankItem> items = rerankWithTimeout(reranker, bo.getQuery(), texts,
                        rerankModel.getId());
                    List<KnowledgeSearchHitVo> reordered = new ArrayList<>(items.size());
                    for (RerankService.RerankItem item : items) {
                        KnowledgeSearchHitVo hit = scored.get(item.index());
                        hit.setRerankScore(item.score());
                        reordered.add(hit);
                    }
                    candidates = reordered;
                } catch (RerankTimeoutException e) {
                    degraded.add(DegradedReason.RERANK_TIMEOUT);
                    log.warn("[知识库] 重排序超时,回退 RRF 粗排: {}", e.getMessage());
                } catch (Exception e) {
                    degraded.add(DegradedReason.RERANK_ERROR);
                    log.warn("[知识库] 重排序失败,回退 RRF 粗排: {}", e.getMessage());
                }
            }
        }
        List<KnowledgeSearchHitVo> result = candidates.stream().limit(topK).collect(Collectors.toCollection(ArrayList::new));

        // 相关度阈值:按本次实际执行阶段选择作用对象(精排分 → 余弦 → 无可比分不生效),
        // 不按配置硬选——精排临时不可用时若仍按精排阈值比,整批结果会被砍空且无从解释
        Double vecThreshold = kb.getScoreThreshold() == null ? null : kb.getScoreThreshold().doubleValue();
        Double rrThreshold = kb.getRerankThreshold() == null ? null : kb.getRerankThreshold().doubleValue();
        ScoreThresholdPolicy.Decision decision = ScoreThresholdPolicy.apply(result, vecThreshold, rrThreshold);
        result = new ArrayList<>(decision.kept());
        if (decision.inactive()) {
            // 配了阈值却没起作用:必须留痕,否则运营会以为"阈值已生效"而误判召回质量
            degraded.add(DegradedReason.THRESHOLD_INACTIVE);
            log.debug("[知识库] {} 配置了阈值但本次无可比分数(仅全文命中),阈值未生效", kb.getId());
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }
        recordHitCounts(result);
        if (cacheKey != null) {
            int ttl = knowledgeProperties.getRagCacheTtlSeconds() == null ? 60 : knowledgeProperties.getRagCacheTtlSeconds();
            try {
                RedisUtils.setCacheObject(cacheKey, result, Duration.ofSeconds(ttl));
            } catch (Exception e) {
                degraded.add(DegradedReason.CACHE_BYPASS);
                log.warn("[知识库] 检索结果写缓存失败,不影响本次返回: {}", e.getMessage());
            }
        }
        return new SearchOutcome(result, degraded, decision.target(),
            vectorHitCount, keywordHitCount, System.currentTimeMillis() - startedAt);
    }

    /**
     * 精排调用带硬超时:精排是增强项,模型抖动不该把整轮检索拖到几十秒。
     * 超时后中断任务并抛 {@link RerankTimeoutException},由调用方回退 RRF 粗排。
     */
    private List<RerankService.RerankItem> rerankWithTimeout(RerankService reranker, String query,
                                                             List<String> texts, Long modelId) throws Exception {
        int timeoutMs = knowledgeProperties.getRerankTimeoutMs() == null
            ? 1500 : Math.max(knowledgeProperties.getRerankTimeoutMs(), 200);
        // 队列为 0 的池:排不进去立刻抛 RejectedExecutionException(按 rerank_error 处理),
        // 而不是让请求在队列里等到超时才发现白等
        java.util.concurrent.CompletableFuture<List<RerankService.RerankItem>> future =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> reranker.rerank(query, texts, modelId), rerankExecutor);
        try {
            return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new RerankTimeoutException("精排超过 " + timeoutMs + "ms 未返回");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    /**
     * 切代读竞态(分块读到旧代、文档指针读到新代),需要整次重试
     */
    private static class GenerationRaceException extends RuntimeException {
    }

    /**
     * 精排超时(与普通异常区分:超时说明模型还在跑,异常说明模型/配置有问题)
     */
    private static class RerankTimeoutException extends RuntimeException {
        RerankTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * 命中次数异步累计(静默失败,不阻塞检索)
     */
    private void recordHitCounts(List<KnowledgeSearchHitVo> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<Long> ids = hits.stream().map(KnowledgeSearchHitVo::getChunkId).filter(Objects::nonNull).toList();
        hitCountWorker.execute(() -> {
            try {
                chunkMapper.incrementHitCount(ids);
            } catch (Exception e) {
                log.debug("[知识库] 命中次数累计失败: {}", e.getMessage());
            }
        });
    }

    @Override
    public void recordZeroHit(Long agentId, Long sessionId, Long userId,
                              String originalQuery, String rewrittenQuery, List<Long> knowledgeIds) {
        hitCountWorker.execute(() -> {
            try {
                AgentKbZeroHitLog entry = new AgentKbZeroHitLog();
                entry.setAgentId(agentId);
                entry.setSessionId(sessionId);
                entry.setUserId(userId);
                entry.setOriginalQuery(ChatTexts.truncate(originalQuery, 480));
                entry.setRewrittenQuery(ChatTexts.truncate(rewrittenQuery, 480));
                entry.setKnowledgeIds(knowledgeIds == null ? "" :
                    knowledgeIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
                entry.setCreateTime(new Date());
                zeroHitLogMapper.insert(entry);
            } catch (Exception e) {
                log.debug("[知识库] 零命中日志记录失败: {}", e.getMessage());
            }
        });
    }

    @Override
    public PageResult<AgentKbZeroHitLogVo> queryZeroHitPage(Long agentId, String keyword, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentKbZeroHitLog> wrapper = new LambdaQueryWrapper<AgentKbZeroHitLog>()
            .eq(agentId != null, AgentKbZeroHitLog::getAgentId, agentId)
            .and(StringUtils.isNotBlank(keyword), w -> w
                .like(AgentKbZeroHitLog::getOriginalQuery, keyword)
                .or().like(AgentKbZeroHitLog::getRewrittenQuery, keyword))
            .orderByDesc(AgentKbZeroHitLog::getId);
        Page<AgentKbZeroHitLogVo> page = zeroHitLogMapper.selectVoPage(pageQuery.build(), wrapper);
        // 批量解析 Agent 名称用于展示
        List<Long> agentIds = page.getRecords().stream().map(AgentKbZeroHitLogVo::getAgentId)
            .filter(Objects::nonNull).distinct().toList();
        if (!agentIds.isEmpty()) {
            Map<Long, String> nameMap = agentInfoMapper.selectByIds(agentIds).stream()
                .collect(Collectors.toMap(AgentInfo::getId, a -> StringUtils.defaultString(a.getName(), "-")));
            page.getRecords().forEach(v -> v.setAgentName(nameMap.getOrDefault(v.getAgentId(), "-")));
        }
        return PageResult.build(page.getRecords(), page.getTotal());
    }


    @Override
    public void recordQueryLog(KbQueryLogBo bo) {
        if (bo == null || bo.getOutcome() == null) {
            return;
        }
        hitCountWorker.execute(() -> {
            try {
                SearchOutcome outcome = bo.getOutcome();
                AgentKbQueryLog row = new AgentKbQueryLog();
                row.setAgentId(bo.getAgentId());
                row.setSessionId(bo.getSessionId());
                row.setUserId(bo.getUserId());
                row.setKnowledgeIds(bo.getKnowledgeIds() == null ? "" : bo.getKnowledgeIds().stream()
                    .filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(",")));
                String original = ChatTexts.truncate(bo.getOriginalQuery(), 480);
                row.setQueryText(original);
                row.setRewrittenQuery(ChatTexts.truncate(
                    StringUtils.defaultString(bo.getRetrievalQuery(), original), 480));
                row.setQueryHash(queryHash(original));
                row.setResultCount(outcome.hits() == null ? 0 : outcome.hits().size());
                row.setZeroHit(outcome.hits() == null || outcome.hits().isEmpty() ? 1 : 0);
                Double top = outcome.topScore();
                row.setTop1Score(top == null || top.isNaN() || top.isInfinite() ? null
                    : java.math.BigDecimal.valueOf(top).setScale(6, java.math.RoundingMode.HALF_UP));
                row.setScoreType(scoreTypeOf(outcome));
                row.setVectorHits(outcome.vectorHits());
                row.setKeywordHits(outcome.keywordHits());
                row.setDegraded(ChatTexts.truncate(outcome.degradedCodeText(), 255));
                row.setRetrievalMs((int) Math.min(outcome.elapsedMs(), Integer.MAX_VALUE));
                row.setCreateTime(new Date());
                queryLogMapper.insert(row);
            } catch (Exception e) {
                // 日志是观测手段,绝不能因为它写失败而影响对话
                log.debug("[知识库] 检索日志写入失败: {}", e.getMessage());
            }
        });
    }

    @Override
    public PageResult<AgentKbQueryLogVo> queryLogPage(Long agentId, String keyword, Boolean zeroHit,
                                                      PageQuery pageQuery) {
        LambdaQueryWrapper<AgentKbQueryLog> wrapper = new LambdaQueryWrapper<AgentKbQueryLog>()
            .eq(agentId != null, AgentKbQueryLog::getAgentId, agentId)
            .eq(zeroHit != null, AgentKbQueryLog::getZeroHit, Boolean.TRUE.equals(zeroHit) ? 1 : 0)
            .and(StringUtils.isNotBlank(keyword), w -> w
                .like(AgentKbQueryLog::getQueryText, keyword)
                .or().like(AgentKbQueryLog::getRewrittenQuery, keyword))
            .orderByDesc(AgentKbQueryLog::getId);
        Page<AgentKbQueryLogVo> page = queryLogMapper.selectVoPage(pageQuery.build(), wrapper);
        List<Long> agentIds = page.getRecords().stream().map(AgentKbQueryLogVo::getAgentId)
            .filter(Objects::nonNull).distinct().toList();
        if (!agentIds.isEmpty()) {
            Map<Long, String> nameMap = agentInfoMapper.selectByIds(agentIds).stream()
                .collect(Collectors.toMap(AgentInfo::getId, a -> StringUtils.defaultString(a.getName(), "-")));
            page.getRecords().forEach(v -> v.setAgentName(nameMap.getOrDefault(v.getAgentId(), "-")));
        }
        // 降级串拆成数组,前端直接渲染标签,不用在页面里做字符串解析
        page.getRecords().forEach(v -> v.setDegradedReasons(StringUtils.isBlank(v.getDegraded())
            ? List.of() : Arrays.stream(v.getDegraded().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList()));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 读"可见分块 + 对应文档":代次过滤在这里统一裁决。
     *
     * <p>向量库不存代次,只能在应用层比:分块代次必须等于文档的 current active_generation。
     * 重建期间新代先写库、指针后切,所以这段时间新代不可见;切换后旧代被清理。</p>
     *
     * @param rawCount 过滤前的分块数(供调用方判断"是否有命中但全被判过期")
     */
    private ChunkAndDoc loadChunksAndDocs(List<Long> chunkIds) {
        List<AgentKbChunk> raw = chunkMapper.selectByIds(chunkIds).stream()
            .filter(c -> (c.getEnabled() == null || c.getEnabled() == 1)
                && !"p".equals(c.getChunkRole()))
            .toList();
        if (raw.isEmpty()) {
            return new ChunkAndDoc(Map.of(), Map.of(), 0);
        }
        List<Long> docIds = raw.stream().map(AgentKbChunk::getDocumentId).filter(Objects::nonNull)
            .distinct().toList();
        Map<Long, AgentKnowledgeDocument> docs = documentMapper.selectByIds(docIds).stream()
            .collect(Collectors.toMap(AgentKnowledgeDocument::getId, Function.identity()));
        Map<Long, AgentKbChunk> visible = raw.stream()
            .filter(c -> isActiveGeneration(c, docs))
            .collect(Collectors.toMap(AgentKbChunk::getId, Function.identity()));
        return new ChunkAndDoc(visible, docs, raw.size());
    }

    /**
     * 检索用的分块与文档快照
     */
    private record ChunkAndDoc(Map<Long, AgentKbChunk> chunks,
                               Map<Long, AgentKnowledgeDocument> docs,
                               int rawCount) {
    }

    /**
     * 分块是否属于其文档的生效代次。
     *
     * <p>用 {@code ==} 而不是 {@code >=}:重建时新代分块先写库、指针后切,
     * 这段时间新代必须不可见,否则用户会看到半新半旧的知识。</p>
     */
    private static boolean isActiveGeneration(AgentKbChunk chunk,
                                              Map<Long, AgentKnowledgeDocument> docMap) {
        AgentKnowledgeDocument doc = docMap.get(chunk.getDocumentId());
        if (doc == null) {
            // 文档已删除(逻辑删),分块是孤儿
            return false;
        }
        int generation = chunk.getGeneration() == null ? 1 : chunk.getGeneration();
        int active = doc.getActiveGeneration() == null ? 1 : doc.getActiveGeneration();
        return generation == active;
    }

    /**
     * 首条命中的分数口径;无命中时退回本次阈值目标(都没有则 null)
     */
    private String scoreTypeOf(SearchOutcome outcome) {
        if (outcome.hits() != null && !outcome.hits().isEmpty()) {
            String type = outcome.hits().get(0).getScoreType();
            if (StringUtils.isNotBlank(type)) {
                return type;
            }
        }
        return outcome.thresholdAppliedOn() == null ? null : outcome.thresholdAppliedOn().code();
    }

    /**
     * 查询指纹:去空白 + 小写 + 折叠连续空格后取 md5,用于聚合"同一类问题"
     */
    private static String queryHash(String query) {
        String normalized = StringUtils.defaultString(query).trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
        return org.springframework.util.DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析知识库配置的重排序模型(按名称匹配启用的 agent_model 配置)
     */
    private AgentModel resolveRerankModel(AgentKnowledge kb) {
        if (StringUtils.isBlank(kb.getRerankModel())) {
            return null;
        }
        return modelMapper.selectOne(new LambdaQueryWrapper<AgentModel>()
            .eq(AgentModel::getName, kb.getRerankModel())
            .eq(AgentModel::getStatus, "0")
            .orderByDesc(AgentModel::getId)
            .last("limit 1"));
    }

    private List<String> findBoundAgents(Long knowledgeId) {
        List<AgentInfo> agents = agentInfoMapper.selectList(new LambdaQueryWrapper<AgentInfo>()
            .select(AgentInfo::getId, AgentInfo::getName, AgentInfo::getKnowledgeIdsStr)
            .isNotNull(AgentInfo::getKnowledgeIdsStr)
            .ne(AgentInfo::getKnowledgeIdsStr, ""));
        String target = String.valueOf(knowledgeId);
        return agents.stream()
            .filter(a -> Arrays.stream(a.getKnowledgeIdsStr().split(","))
                .map(String::trim).anyMatch(target::equals))
            .map(AgentInfo::getName)
            .filter(Objects::nonNull)
            .toList();
    }

    private void checkNameUnique(String name, Long excludeId, Long workspaceId) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        LambdaQueryWrapper<AgentKnowledge> wrapper = new LambdaQueryWrapper<AgentKnowledge>()
            .eq(AgentKnowledge::getName, name)
            .eq(AgentKnowledge::getKbScope, "normal")
            // 同空间内名称唯一;全局知识库(workspace_id IS NULL)名称全局唯一
            .and(w -> {
                if (workspaceId == null) {
                    w.isNull(AgentKnowledge::getWorkspaceId);
                } else {
                    w.eq(AgentKnowledge::getWorkspaceId, workspaceId)
                        .or().isNull(AgentKnowledge::getWorkspaceId);
                }
            })
            .ne(excludeId != null, AgentKnowledge::getId, excludeId);
        if (knowledgeMapper.selectCount(wrapper) > 0) {
            throw new ServiceException("知识库名称已存在:" + name);
        }
    }

    /**
     * 分块单位归一:留空按存量口径 char;非法值直接拒绝
     */
    private static String normalizeChunkUnit(String unit) {
        if (StringUtils.isBlank(unit)) {
            return com.forgeagent.rag.service.document.TextChunker.UNIT_CHAR;
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        if (!com.forgeagent.rag.service.document.TextChunker.isKnownUnit(normalized)) {
            throw new ServiceException("分块单位不支持:" + unit + ",可选 char / token");
        }
        return normalized;
    }

    /**
     * 分块策略归一:留空按结构感知;非法值直接拒绝
     */
    private static String normalizeSplitStrategy(String strategy) {
        if (StringUtils.isBlank(strategy)) {
            return com.forgeagent.rag.service.document.TextChunker.STRATEGY_STRUCTURE;
        }
        String normalized = strategy.trim().toLowerCase(Locale.ROOT);
        if (!com.forgeagent.rag.service.document.TextChunker.isKnownStrategy(normalized)) {
            throw new ServiceException("分块策略不支持:" + strategy + ",可选 structure / fixed");
        }
        return normalized;
    }

    /**
     * 计算可见范围:null 表示全部(超管或已校验成员的指定空间),否则为用户已加入的空间集合
     */
    private List<Long> resolveScope(Long workspaceId) {
        if (workspaceId != null) {
            permissionHelper.checkWsMember(workspaceId);
            return null;
        }
        if (LoginHelper.isSuperAdmin()) {
            return null;
        }
        return workspaceMemberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId()))
            .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
    }

    /**
     * 校验当前用户对该空间知识库有读权限(全局知识库对所有空间成员可读,超管放行)
     */
    private void checkWsReadable(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (workspaceId == null) {
            // 全局知识库所有已登录用户可读
            return;
        }
        permissionHelper.checkWsMember(workspaceId);
    }

    /**
     * 校验当前用户对该空间知识库有写权限(全局知识库仅超管可写)
     */
    private void checkWsWritable(Long workspaceId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (workspaceId == null) {
            throw new ServiceException("仅超级管理员可管理全局知识库");
        }
        permissionHelper.checkWsDeveloper(workspaceId);
    }
}
