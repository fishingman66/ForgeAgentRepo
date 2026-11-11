package com.forgeagent.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.memory.domain.AgentMemory;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.memory.domain.bo.AgentMemoryBo;
import com.forgeagent.memory.config.MemoryVectorProperties;
import com.forgeagent.callcenter.common.CsPiiMasker;
import com.forgeagent.memory.domain.vo.AgentMemoryVo;
import com.forgeagent.memory.mapper.AgentMemoryMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.kg.service.KgScope;
import com.forgeagent.kg.service.KgService;
import com.forgeagent.kg.service.KgTriple;
import com.forgeagent.kg.service.KgNode;
import com.forgeagent.kg.service.KgEdge;
import com.forgeagent.memory.config.MemoryGovernanceProperties;
import com.forgeagent.memory.mapper.MemoryGovernanceMapper;
import com.forgeagent.memory.service.IAgentMemoryService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.agentcore.service.AgentScopeModelFactory;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 长期记忆 Service 实现(P2 / C3 分层增强)。
 *
 * <p>注入:按「重要性 × 时间衰减 + 命中次数」混合评分排序,受字符预算裁剪,
 * 不再简单取最近 10 条;命中后回写 hit_count / last_hit_at 形成反馈学习。</p>
 *
 * <p>提取:模型输出带 factKey 的结构化事实,同一事实键做冲突替代(更新旧值而非追加),
 * 兼容旧版纯字符串数组输出。提取失败静默,不影响对话主流程。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AgentMemoryServiceImpl implements IAgentMemoryService {

    /** 注入提示词的最大记忆条数 */
    private static final int MAX_INJECT = 10;

    /** 注入提示词的字符预算(粗略 4 字符 ≈ 1 token),避免长期记忆挤占上下文 */
    private static final int MAX_INJECT_CHARS = 1500;

    /** 排序候选池上限 */
    private static final int CANDIDATE_LIMIT = 100;

    /** 时间衰减半衰尺度(天) */
    private static final double DECAY_DAYS = 30.0;

    /** 单轮对话最多提取的记忆条数 */
    private static final int MAX_EXTRACT = 3;

    /** 单轮对话最多接受的三元组条数(与提示词里的数字一致;实际兜底上限在 forge-kg 的 KgService 按配置生效) */
    private static final int MAX_TRIPLES = 6;

    /** 命中次数对评分的贡献上限,防止「越常被注入越容易被注入」的无限自我强化 */
    private static final double HIT_SCORE_CAP = 2.0;

    /** 同一条记忆命中反馈的最小写入间隔(分钟),削减每轮对话的写放大 */
    private static final int HIT_THROTTLE_MINUTES = 5;

    static final String EXTRACT_SYS_PROMPT =
        "你是记忆提取助手。[记忆提取指令] 从用户提供的这轮对话中提取值得长期记住的信息与实体关系,"
            + "输出 JSON 对象:{\"facts\":[{\"key\":\"事实类别短键\",\"content\":\"一条简洁陈述\","
            + "\"importance\":1到10的整数}],\"triples\":[{\"subject\":\"主语实体\","
            + "\"relation\":\"关系动词短语\",\"object\":\"宾语实体\","
            + "\"fact\":\"可选,对应 facts 里某条的 key\"}]}。"
            + "facts 最多 " + MAX_EXTRACT
            + " 项,同一事实再次出现时沿用相同 key,便于平台覆盖旧值;triples 最多 " + MAX_TRIPLES
            + " 项,subject/object 用 2~12 字的实体名(人、项目、工具、组织),relation 用 2~8 字的动词短语"
            + "(如 负责、使用、偏好、居住在),只输出对话里明确出现的关系,不要推测;"
            + "不得输出手机号、身份证号、银行卡号、邮箱等敏感信息原文,对话中出现时只写脱敏描述"
            + "或省略该类信息;没有值得记住的内容时输出 {\"facts\":[],\"triples\":[]}。"
            + "只输出 JSON 对象,不要输出任何其他内容。";

    /** 存量补建专用提示词:一次处理多条已有记忆,三元组用 fact 字段回指记忆编号 */
    static final String TRIPLE_SYS_PROMPT =
        "你是知识图谱抽取助手。输入是一批已有记忆,每条前带编号。把记忆里明确出现过的实体关系抽成三元组,"
            + "输出 JSON 对象:{\"triples\":[{\"subject\":\"主语实体\",\"relation\":\"关系动词短语\","
            + "\"object\":\"宾语实体\",\"fact\":\"该关系来自的记忆编号\"}]}。"
            + "subject/object 用 2~12 字的实体名,relation 用 2~8 字的动词短语;"
            + "只输出记忆里明确出现的关系,不要推测、不要编造;"
            + "不得输出手机号、身份证号、银行卡号、邮箱等敏感信息原文;"
            + "没有可抽取的关系时输出 {\"triples\":[]}。只输出 JSON 对象,不要输出任何其他内容。";

    private static final com.fasterxml.jackson.databind.ObjectMapper FasterxmlMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 记忆提取执行器(daemon):单线程 + 有界队列,队列满时丢弃任务 */
    private static final java.util.concurrent.ThreadPoolExecutor EXTRACTOR =
        new java.util.concurrent.ThreadPoolExecutor(1, 2, 60, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "agent-memory-extract");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    private final AgentMemoryMapper memoryMapper;
    private final AgentConfigCacheService agentConfigCache;
    private final WorkspacePermissionHelper permissionHelper;
    /** P3 / M2':MySQL 向量列语义召回(未启用时全部旁路) */
    private final MemoryVectorService memoryVectorService;
    private final MemoryVectorProperties vectorProperties;
    /** P3 / M3:知识图谱本体在 forge-kg;这里只保留记忆侧的注入适配与补建入口 */
    private final KgService kgService;
    private final MemoryGraphInjection graphInjection;
    /** P3 / M4:记忆治理(归档/TTL/配额)。 */
    private final MemoryGovernanceService governanceService;
    private final MemoryGovernanceMapper governanceMapper;
    private final MemoryGovernanceProperties governanceProperties;

    @Override
    public PageResult<AgentMemoryVo> queryPageList(Long agentId, String keyword, PageQuery pageQuery) {
        checkAgentAccess(agentId, false);
        LambdaQueryWrapper<AgentMemory> wrapper = new LambdaQueryWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)
            .like(StringUtils.isNotBlank(keyword), AgentMemory::getContent, keyword)
            .orderByDesc(AgentMemory::getId);
        Page<AgentMemoryVo> page = memoryMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public Boolean insertByBo(AgentMemoryBo bo) {
        checkAgentAccess(bo.getAgentId(), true);
        AgentMemory memory = MapstructUtils.convert(bo, AgentMemory.class);
        // 手动添加与自动提取走同一道脱敏兜底:提示词约束挡不住的原文,落库前统一打码
        memory.setContent(CsPiiMasker.mask(memory.getContent()));
        if (existsMemory(memory.getAgentId(), null, memory.getContent())) {
            throw new ServiceException("相同内容的记忆已存在");
        }
        // 手动添加同样写入事实键与默认权重,与自动提取走同一套排序逻辑
        memory.setFactKey(StringUtils.defaultIfBlank(memory.getFactKey(), normalizeKey(memory.getContent())));
        memory.setImportance(memory.getImportance() == null ? 5 : clampImportance(memory.getImportance()));
        memory.setHitCount(memory.getHitCount() == null ? 0 : memory.getHitCount());
        boolean inserted = memoryMapper.insert(memory) > 0;
        if (inserted) {
            memoryVectorService.vectorizeAsync(memory.getId());
        }
        return inserted;
    }

    @Override
    public Boolean deleteWithValidByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        List<AgentMemory> memories = memoryMapper.selectByIds(ids);
        memories.stream().map(AgentMemory::getAgentId).distinct()
            .forEach(agentId -> checkAgentAccess(agentId, true));
        return memoryMapper.deleteByIds(ids) > 0;
    }

    @Override
    public Boolean clearByAgent(Long agentId) {
        checkAgentAccess(agentId, true);
        return memoryMapper.delete(new LambdaQueryWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)) > 0;
    }

    private void checkAgentAccess(Long agentId, boolean write) {
        com.forgeagent.agentcore.domain.AgentInfo agent = agentConfigCache.getAgentById(agentId);
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        if (write) {
            permissionHelper.checkWsDeveloper(agent.getWorkspaceId());
        } else {
            permissionHelper.checkWsMember(agent.getWorkspaceId());
        }
    }

    /**
     * 分层召回:候选池按混合评分选出注入条目,再按事实键稳定排序输出。
     *
     * <p>评分决定「选哪几条」,排序只决定「以什么顺序写进提示词」——后者用 factKey + id
     * 固定,避免 hitCount 回写与时间衰减让跨轮前缀抖动,破坏供应商侧 KV Cache。</p>
     *
     * <p>P3 / M2':开启语义向量且带有本轮问题时,评分候选池与向量召回按
     * {@code semanticWeight × 相似度 + (1-weight) × 归一化评分} 融合。
     * 向量服务不可用、维度不符、配置关闭时自动回退纯评分,不影响对话。</p>
     */
    @Override
    public String buildMemoryBlock(Long agentId, Long userId, String query) {
        LambdaQueryWrapper<AgentMemory> wrapper = new LambdaQueryWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)
            // 先按 id 倒序取最新候选,再在应用层评分;没有 ORDER BY 的 limit 会拿到最旧的一批,
            // 记忆超过候选池上限后新沉淀的事实将永远进不了注入
            .orderByDesc(AgentMemory::getId)
            .last("limit " + CANDIDATE_LIMIT);
        MemoryScope.applyReadScope(wrapper, userId);
        // M4:显式 TTL——expire_at 已过期的记忆不注入;ttl-days 隐式过期由治理任务归档兜住
        wrapper.and(w -> w.isNull(AgentMemory::getExpireAt)
            .or().gt(AgentMemory::getExpireAt, LocalDateTime.now()));
        List<AgentMemory> memories = memoryMapper.selectList(wrapper);
        if (memories.isEmpty()) {
            return "";
        }
        boolean useVector = memoryVectorService != null && memoryVectorService.enabled()
            && StringUtils.isNotBlank(query);
        List<Scored> scored;
        if (useVector) {
            // 补偿扫描异步投递,不阻塞本轮;语义召回失败返回空列表,融合退化为评分排序
            memoryVectorService.ensurePendingAsync(agentId, userId);
            scored = fuse(memories, memoryVectorService.search(agentId, userId, query,
                vectorProperties.getTopK()));
        } else {
            scored = new ArrayList<>(memories.size());
            for (AgentMemory memory : memories) {
                scored.add(new Scored(memory, scoreOf(memory)));
            }
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        }

        List<Scored> selected = new ArrayList<>();
        List<Long> hitIds = new ArrayList<>();
        List<String> selectedContents = new ArrayList<>();
        // 图增强的「关联背景」占用记忆块内部配额:先扣出来,避免总注入量超标挤占上下文
        int graphReserve = graphInjection == null ? 0 : Math.max(0, graphInjection.reserveChars());
        int contentBudget = Math.max(0, MAX_INJECT_CHARS - graphReserve);
        int used = 0;
        for (Scored item : scored) {
            if (selected.size() >= MAX_INJECT) {
                break;
            }
            // 预算按内容长度算,与最终输出顺序无关
            int lineChars = StringUtils.defaultString(item.memory().getContent()).length() + 4;
            if (used + lineChars > contentBudget) {
                break;
            }
            selected.add(item);
            selectedContents.add(StringUtils.defaultString(item.memory().getContent()));
            used += lineChars;
            if (item.memory().getId() != null) {
                hitIds.add(item.memory().getId());
            }
        }
        if (selected.isEmpty()) {
            return "";
        }
        selected.sort(java.util.Comparator
            .comparing((Scored s) -> StringUtils.defaultString(s.memory().getFactKey()))
            .thenComparing(s -> s.memory().getId() == null ? 0L : s.memory().getId()));
        StringBuilder sb = new StringBuilder("[长期记忆]\n以下是历史对话沉淀的关于该用户的记忆,回答时请参考:\n");
        int index = 1;
        for (Scored item : selected) {
            // 读侧再兜一层:历史遗留的明文记忆也不进模型上下文
            sb.append(index++).append(". ").append(CsPiiMasker.mask(item.memory().getContent())).append('\n');
        }
        // P3 / M3:图增强追加在末尾 —— 前面的事实条目按 factKey 稳定排序,动态内容放尾部
        // 不会让跨轮前缀整体位移,供应商侧 KV Cache 仍可命中
        if (graphReserve > 0) {
            String graphBlock = graphInjection.build(
                KgScope.agent(agentId, userId), selectedContents, graphReserve);
            if (StringUtils.isNotBlank(graphBlock)) {
                sb.append('\n').append(graphBlock).append('\n');
            }
        }
        recordHits(hitIds);
        return sb.toString().trim();
    }

    /**
     * 评分候选池 + 语义召回融合:按 id 去重,超出候选池的向量命中也允许补进来。
     * 评分先归一化到 0~1 再与余弦相似度加权,避免两个量纲直接相加。
     */
    private List<Scored> fuse(List<AgentMemory> candidates, List<MemoryVectorService.Hit> hits) {
        Map<Long, AgentMemory> pool = new LinkedHashMap<>();
        for (AgentMemory memory : candidates) {
            if (memory.getId() != null) {
                pool.put(memory.getId(), memory);
            }
        }
        Map<Long, Double> similarity = new HashMap<>();
        for (MemoryVectorService.Hit hit : hits) {
            AgentMemory memory = hit.memory();
            if (memory == null || memory.getId() == null) {
                continue;
            }
            pool.putIfAbsent(memory.getId(), memory);
            similarity.put(memory.getId(), hit.score());
        }
        double maxScore = pool.values().stream().mapToDouble(AgentMemoryServiceImpl::scoreOf)
            .max().orElse(1.0);
        double weight = vectorProperties == null ? 0.6
            : Math.max(0, Math.min(1, vectorProperties.getSemanticWeight()));
        List<Scored> result = new ArrayList<>(pool.size());
        for (AgentMemory memory : pool.values()) {
            double normalized = maxScore > 0 ? scoreOf(memory) / maxScore : 0;
            double semantic = similarity.getOrDefault(memory.getId(), 0.0);
            result.add(new Scored(memory, weight * semantic + (1 - weight) * normalized));
        }
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    /** 混合评分(包可见静态,便于单测锁定命中反馈封顶口径) */
    static double scoreOf(AgentMemory memory) {
        double importance = clampImportance(memory.getImportance() == null ? 5 : memory.getImportance());
        double ageDays = 0;
        LocalDateTime created = memory.getCreateTime();
        if (created != null) {
            ageDays = Math.max(0, Duration.between(created, LocalDateTime.now()).toHours() / 24.0);
        }
        double decay = Math.exp(-ageDays / DECAY_DAYS);
        int hits = memory.getHitCount() == null ? 0 : Math.max(0, memory.getHitCount());
        // 命中反馈封顶:hitCount 只提供有限的曝光加成,新记忆仍有冒头机会
        return importance * decay + Math.min(HIT_SCORE_CAP, Math.log1p(hits) * 0.5);
    }

    /** 批量回写命中次数;同一条 5 分钟内只回写一次,失败只记日志,不影响对话 */
    private void recordHits(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            memoryMapper.update(new AgentMemory(), new LambdaUpdateWrapper<AgentMemory>()
                .in(AgentMemory::getId, ids)
                .apply("(last_hit_at IS NULL OR last_hit_at < DATE_SUB(NOW(), INTERVAL "
                    + HIT_THROTTLE_MINUTES + " MINUTE))")
                .setSql("hit_count = IFNULL(hit_count,0) + 1, last_hit_at = NOW()"));
        } catch (Exception e) {
            log.warn("[AgentMemory] 命中反馈写入失败: {}", e.getMessage());
        }
    }

    @Override
    public void extractAsync(Long agentId, Long workspaceId, Long userId, Long sessionId,
                             String userContent, String assistantContent) {
        if (StringUtils.isBlank(userContent) && StringUtils.isBlank(assistantContent)) {
            return;
        }
        EXTRACTOR.execute(() -> {
            try {
                doExtract(agentId, workspaceId, userId, sessionId, userContent, assistantContent);
            } catch (Exception e) {
                log.warn("[AgentMemory] 记忆提取失败(agent={}): {}", agentId, e.getMessage());
            }
        });
    }

    private void doExtract(Long agentId, Long workspaceId, Long userId, Long sessionId,
                           String userContent, String assistantContent) {
        AgentModel model = resolveAgentModel(agentId);
        if (model == null) {
            return;
        }
        Model asModel = AgentScopeModelFactory.build(model.getBaseUrl(), model.getApiKey(),
            model.getModelName(), null, null);
        ReActAgent extractor = ReActAgent.builder()
            .name("memory-extractor")
            .model(asModel)
            .maxIters(2)
            .sysPrompt(EXTRACT_SYS_PROMPT)
            .build();
        String dialogue = "用户: " + safe(userContent) + "\n助手: " + safe(assistantContent);
        Msg reply = extractor.call(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(dialogue)
                .build())
            .block(Duration.ofSeconds(60));
        if (reply == null || StringUtils.isBlank(reply.getTextContent())) {
            return;
        }
        Extraction extraction = parseExtraction(reply.getTextContent());
        List<Fact> facts = extraction.facts();
        // 事实键按 agent+user 隔离:平台级记忆(userId 为空)与本人记忆互不覆盖
        Set<String> knownKeys = loadExistingFactKeys(agentId, userId);
        // 事实键 → 记忆ID,供三元组回指证据(冲突替代的分支拿不到 id,对应三元组证据为空)
        Map<String, Long> keyToMemoryId = new HashMap<>();
        int inserted = 0;
        int replaced = 0;
        for (Fact fact : facts) {
            if (inserted >= MAX_EXTRACT) {
                break;
            }
            String content = CsPiiMasker.mask(fact.content());
            if (knownKeys.contains(fact.key())) {
                // 冲突替代:同一事实键只保留最新表述,不追加重复记忆。
                // 只覆盖「同一 agent + 同一用户」的行,早期版本缺 userId 条件会改写他人记忆
                AgentMemory update = new AgentMemory();
                update.setContent(content);
                update.setImportance(fact.importance());
                LambdaUpdateWrapper<AgentMemory> updater =
                    MemoryScope.conflictUpdate(agentId, fact.key(), userId);
                if (memoryVectorService.enabled()) {
                    // 内容换了旧向量就与新文本不匹配:标失效,由下一轮补偿扫描重算
                    updater.set(AgentMemory::getEmbeddingModel, MemoryVectorService.STALE_MODEL);
                }
                memoryMapper.update(update, updater);
                replaced++;
                continue;
            }
            AgentMemory memory = new AgentMemory();
            memory.setAgentId(agentId);
            memory.setWorkspaceId(workspaceId);
            memory.setUserId(userId);
            memory.setContent(content);
            memory.setFactKey(fact.key());
            memory.setImportance(fact.importance());
            memory.setHitCount(0);
            memory.setSourceSessionId(sessionId);
            // M4:配额——写入前先淘汰超额低分记忆,保证最新事实永远进得来
            if (governanceService != null) {
                governanceService.evictBeforeInsert(agentId, userId, 1);
            }
            memoryMapper.insert(memory);
            memoryVectorService.vectorizeAsync(memory.getId());
            if (memory.getId() != null) {
                keyToMemoryId.put(fact.key(), memory.getId());
            }
            knownKeys.add(fact.key());
            inserted++;
        }
        ingestTriples(agentId, userId, extraction.triples(), keyToMemoryId);
        if (inserted > 0 || replaced > 0) {
            log.info("[AgentMemory] 会话 {} 沉淀 {} 条新记忆,替代 {} 条旧事实", sessionId, inserted, replaced);
        }
    }

    /** 三元组落图:证据记忆按 fact 键回指;图谱关闭或无三元组时直接跳过 */
    private void ingestTriples(Long agentId, Long userId, List<TripleDraft> drafts,
                               Map<String, Long> keyToMemoryId) {
        if (drafts.isEmpty() || kgService == null || !kgService.enabled()) {
            return;
        }
        List<KgTriple> triples = new ArrayList<>(drafts.size());
        for (TripleDraft draft : drafts) {
            Long evidence = StringUtils.isBlank(draft.factKey())
                ? null : keyToMemoryId.get(draft.factKey().trim());
            // forge-kg 不依赖业务域的脱敏器,文本在组装三元组前由记忆侧统一打码
            triples.add(new KgTriple(
                CsPiiMasker.mask(draft.subject()),
                CsPiiMasker.mask(draft.relation()),
                CsPiiMasker.mask(draft.object()),
                evidence));
        }
        kgService.ingest(KgScope.agent(agentId, userId), triples);
    }

    /**
     * 解析模型输出:新格式为 {@code {"facts":[...],"triples":[...]}},同时兼容
     * 旧版「只输出事实数组」与「只输出三元组对象」两种形态。
     *
     * <p>抽出日期:两种形态都可能出现(模型不听话 + 存量记忆补建只回三元组),
     * 所以按「{ 与 [ 谁先出现」判断形态,任何一步失败都退回空结果而不是抛异常。</p>
     */
    Extraction parseExtraction(String text) {
        List<Fact> facts = new ArrayList<>();
        List<TripleDraft> triples = new ArrayList<>();
        try {
            String trimmed = StringUtils.trim(text);
            if (StringUtils.isBlank(trimmed)) {
                return new Extraction(facts, triples);
            }
            // 用「谁先出现」判断形态:旧版是 [{...}] 数组,新版是 {"facts":..,"triples":..} 对象。
            // 只按 '{' 判断会把旧版数组里的第一个对象当成整份结果,导致事实全部丢失。
            int arrStart = trimmed.indexOf('[');
            int objStart = trimmed.indexOf('{');
            int arrEnd = trimmed.lastIndexOf(']');
            int objEnd = trimmed.lastIndexOf('}');
            boolean objectFirst = objStart >= 0 && (arrStart < 0 || objStart < arrStart);
            if (objectFirst && objEnd > objStart) {
                com.fasterxml.jackson.databind.JsonNode root =
                    FasterxmlMapper.readTree(trimmed.substring(objStart, objEnd + 1));
                if (root.isObject()) {
                    collectFacts(root.path("facts"), facts);
                    collectTriples(root.path("triples"), triples);
                    return new Extraction(facts, triples);
                }
            }
            if (arrStart >= 0 && arrEnd > arrStart) {
                collectFacts(FasterxmlMapper.readTree(trimmed.substring(arrStart, arrEnd + 1)), facts);
            }
        } catch (Exception e) {
            log.warn("[AgentMemory] 记忆提取结果解析失败: {}", e.getMessage());
        }
        return new Extraction(facts, triples);
    }

    /** 收集事实条目:优先对象数组,兼容纯字符串数组;同 key 只保留第一条 */
    private void collectFacts(com.fasterxml.jackson.databind.JsonNode node, List<Fact> out) {
        if (node == null || !node.isArray()) {
            return;
        }
        Set<String> dedup = new HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode item : node) {
            String content;
            String key;
            int importance = 5;
            if (item.isObject()) {
                key = item.path("key").asText("").trim();
                content = item.path("content").asText("").trim();
                importance = clampImportance(item.path("importance").asInt(5));
            } else {
                content = item.asText("").trim();
                key = "";
            }
            if (content.isEmpty() || content.length() > 1000) {
                continue;
            }
            if (StringUtils.isBlank(key)) {
                key = normalizeKey(content);
            }
            key = key.substring(0, Math.min(128, key.length()));
            if (!dedup.add(key)) {
                continue;
            }
            out.add(new Fact(key, content, importance));
        }
    }

    /** 收集三元组:三个字段缺一即丢弃;fact 为可选回指键 */
    private void collectTriples(com.fasterxml.jackson.databind.JsonNode node, List<TripleDraft> out) {
        if (node == null || !node.isArray()) {
            return;
        }
        Set<String> dedup = new HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String subject = item.path("subject").asText("").trim();
            String relation = item.path("relation").asText("").trim();
            String object = item.path("object").asText("").trim();
            String fact = item.path("fact").asText("").trim();
            if (subject.isEmpty() || relation.isEmpty() || object.isEmpty()) {
                continue;
            }
            if (!dedup.add(subject + '\u0001' + relation + '\u0001' + object)) {
                continue;
            }
            out.add(new TripleDraft(subject, relation, object, fact));
            if (out.size() >= MAX_TRIPLES) {
                return;
            }
        }
    }

    private boolean existsMemory(Long agentId, Long userId, String content) {
        return memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
            .eq(AgentMemory::getAgentId, agentId)
            .eq(userId != null, AgentMemory::getUserId, userId)
            .eq(AgentMemory::getContent, content)) > 0;
    }

    /**
     * 读取「属于当前写入范围」的事实键:userId 非空只取该用户的行,为空只取平台级行。
     * 必须与冲突替代 UPDATE 的过滤条件完全一致,否则会把别人的键误判成已存在而跳过新增。
     */
    private Set<String> loadExistingFactKeys(Long agentId, Long userId) {
        List<AgentMemory> rows = memoryMapper.selectList(MemoryScope.factKeyQuery(agentId, userId));
        Set<String> keys = new HashSet<>();
        rows.forEach(m -> {
            if (StringUtils.isNotBlank(m.getFactKey())) {
                keys.add(m.getFactKey());
            }
        });
        return keys;
    }

    @Override
    public String previewMemoryBlock(Long agentId, Long userId, String query) {
        checkAgentAccess(agentId, false);
        return buildMemoryBlock(agentId, userId, query);
    }

    @Override
    public List<AgentMemoryVo> searchMemories(Long agentId, Long userId, String query, Integer limit) {
        checkAgentAccess(agentId, false);
        if (StringUtils.isBlank(query)) {
            return List.of();
        }
        int k = Math.max(1, Math.min(20, limit == null ? 10 : limit));
        List<AgentMemory> picked = new ArrayList<>();
        if (memoryVectorService != null && memoryVectorService.enabled()) {
            picked = memoryVectorService.search(agentId, userId, query, k)
                .stream().map(MemoryVectorService.Hit::memory).toList();
        }
        if (picked.isEmpty()) {
            // 向量不可用/无命中时回退评分召回(与注入同口径),检索入口不至于空手而归
            LambdaQueryWrapper<AgentMemory> wrapper = new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getAgentId, agentId)
                .orderByDesc(AgentMemory::getId)
                .last("limit " + CANDIDATE_LIMIT);
            MemoryScope.applyReadScope(wrapper, userId);
            wrapper.and(w -> w.isNull(AgentMemory::getExpireAt)
                .or().gt(AgentMemory::getExpireAt, LocalDateTime.now()));
            picked = memoryMapper.selectList(wrapper).stream()
                .sorted(java.util.Comparator.comparingDouble(AgentMemoryServiceImpl::scoreOf).reversed())
                .limit(k)
                .toList();
        }
        // 读侧兜一层脱敏:历史遗留的明文记忆不进管理页明文展示。
        // 手工映射而不是 MapstructUtils:纯单测环境没有 Spring 容器,hutool SpringUtil 拿不到 BeanFactory。
        return picked.stream().map(m -> {
            AgentMemoryVo vo = new AgentMemoryVo();
            vo.setId(m.getId());
            vo.setAgentId(m.getAgentId());
            vo.setWorkspaceId(m.getWorkspaceId());
            vo.setUserId(m.getUserId());
            vo.setContent(CsPiiMasker.mask(m.getContent()));
            vo.setFactKey(m.getFactKey());
            vo.setImportance(m.getImportance());
            vo.setSourceSessionId(m.getSourceSessionId());
            vo.setCreateTime(m.getCreateTime() == null
                ? null : java.sql.Timestamp.valueOf(m.getCreateTime()));
            return vo;
        }).toList();
    }

    @Override
    public long forgetUser(Long agentId, Long userId, String confirm) {
        checkAgentAccess(agentId, true);
        if (!"FORGET".equals(confirm)) {
            throw new ServiceException("遗忘操作需要 confirm=FORGET 二次确认");
        }
        if (userId == null) {
            throw new ServiceException("遗忘必须指定用户,平台级记忆不在遗忘范围");
        }
        long count = memoryMapper.countByUser(agentId, userId);
        // 记忆(含向量三列)物理删;图谱按范围删;两张归档表按归属删——遗忘权要求各层无残留
        memoryMapper.physicalDeleteByUser(agentId, userId);
        kgService.deleteScope(KgScope.agent(agentId, userId));
        governanceMapper.deleteMemoryArchiveByUser(userId);
        governanceMapper.deleteMessageArchiveByUser(userId);
        log.info("[AgentMemory] 用户遗忘完成: agent={} user={}, 删除记忆 {} 条,图谱与归档已级联清理",
            agentId, userId, count);
        return count;
    }

    @Override
    public List<KgNode> graphNodes(Long agentId, Long userId, Integer limit) {
        checkAgentAccess(agentId, false);
        int k = Math.max(1, Math.min(200, limit == null ? 100 : limit));
        return kgService.nodes(KgScope.agent(agentId, userId), k);
    }

    @Override
    public List<KgEdge> graphEdges(Long agentId, Long userId, Integer limit) {
        checkAgentAccess(agentId, false);
        int k = Math.max(1, Math.min(200, limit == null ? 200 : limit));
        return kgService.edges(KgScope.agent(agentId, userId), k);
    }

    @Override
    public Map<String, Long> runGovernance() {
        checkGovernanceEnabled();
        return governanceService.runOnce();
    }

    private void checkGovernanceEnabled() {
        if (governanceProperties == null || !governanceProperties.isEnabled()) {
            throw new ServiceException("记忆治理未启用,请检查 memory.governance.enabled 配置");
        }
    }

    @Override
    public Boolean rebuildGraphAsync(Long agentId, Long userId) {
        checkAgentAccess(agentId, true);
        if (graphInjection == null || !graphInjection.enabled()) {
            throw new ServiceException("知识图谱未启用,请检查 kg.enabled 与 kg.backend 配置");
        }
        EXTRACTOR.execute(() -> {
            try {
                doRebuildGraph(agentId, userId);
            } catch (Exception e) {
                log.warn("[AgentMemory] 图谱补建失败(agent={}): {}", agentId, e.getMessage());
            }
        });
        return true;
    }

    /**
     * 存量记忆补建:分批送模型抽三元组并落图。
     *
     * <p>会真实消耗模型调用(每批一次),所以只做手动触发,不做随对话自动补建;
     * 上限与批量由 {@code memory.graph.rebuild-limit / rebuild-batch} 控制。</p>
     */
    private void doRebuildGraph(Long agentId, Long userId) {
        AgentModel model = resolveAgentModel(agentId);
        if (model == null) {
            return;
        }
        int limit = graphInjection.rebuildLimit();
        List<AgentMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
            .select(AgentMemory::getId, AgentMemory::getContent)
            .eq(AgentMemory::getAgentId, agentId)
            .eq(userId != null, AgentMemory::getUserId, userId)
            .isNull(userId == null, AgentMemory::getUserId)
            .orderByDesc(AgentMemory::getId)
            .last("limit " + limit));
        if (rows.isEmpty()) {
            return;
        }
        Model asModel = AgentScopeModelFactory.build(model.getBaseUrl(), model.getApiKey(),
            model.getModelName(), null, null);
        ReActAgent rebuilder = ReActAgent.builder()
            .name("memory-graph-rebuilder")
            .model(asModel)
            .maxIters(2)
            .sysPrompt(TRIPLE_SYS_PROMPT)
            .build();
        int batch = graphInjection.rebuildBatch();
        int total = 0;
        for (int from = 0; from < rows.size(); from += batch) {
            List<AgentMemory> slice = rows.subList(from, Math.min(rows.size(), from + batch));
            StringBuilder prompt = new StringBuilder("已有记忆(编号. 内容):\n");
            for (int i = 0; i < slice.size(); i++) {
                prompt.append(i + 1).append(". ")
                    .append(CsPiiMasker.mask(slice.get(i).getContent())).append('\n');
            }
            Msg reply = rebuilder.call(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(prompt.toString())
                    .build())
                .block(Duration.ofSeconds(60));
            if (reply == null || StringUtils.isBlank(reply.getTextContent())) {
                continue;
            }
            List<TripleDraft> drafts = parseExtraction(reply.getTextContent()).triples();
            if (drafts.isEmpty()) {
                continue;
            }
            List<KgTriple> triples = new ArrayList<>(drafts.size());
            for (TripleDraft draft : drafts) {
                triples.add(new KgTriple(
                    CsPiiMasker.mask(draft.subject()),
                    CsPiiMasker.mask(draft.relation()),
                    CsPiiMasker.mask(draft.object()),
                    evidenceOf(draft.factKey(), slice)));
            }
            kgService.ingest(KgScope.agent(agentId, userId), triples);
            total += triples.size();
        }
        log.info("[AgentMemory] agent {} 图谱补建完成,处理 {} 条记忆 / 抽取三元组 {} 条",
            agentId, rows.size(), total);
    }

    /** 补建提示词里的 fact 是「记忆编号」,换算成记忆ID;越界或非数字时证据留空 */
    private static Long evidenceOf(String factRef, List<AgentMemory> slice) {
        if (StringUtils.isBlank(factRef)) {
            return null;
        }
        try {
            int index = Integer.parseInt(factRef.trim());
            return index >= 1 && index <= slice.size() ? slice.get(index - 1).getId() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AgentModel resolveAgentModel(Long agentId) {
        com.forgeagent.agentcore.domain.AgentInfo agent = agentConfigCache.getAgentById(agentId);
        if (agent == null || agent.getModelId() == null) {
            return null;
        }
        AgentModel model = agentConfigCache.getModelById(agent.getModelId());
        if (model == null || StringUtils.isBlank(model.getBaseUrl())
            || StringUtils.isBlank(model.getApiKey()) || StringUtils.isBlank(model.getModelName())) {
            return null;
        }
        return model;
    }

    /** 归一化事实键:去空白与标点、转小写后取稳定短哈希 */
    private static String normalizeKey(String text) {
        String normalized = (text == null ? "" : text)
            .replaceAll("[\\s\\p{Punct}]+", "").toLowerCase(Locale.ROOT);
        return "auto-" + Integer.toHexString(normalized.hashCode());
    }

    private static int clampImportance(int value) {
        return Math.max(1, Math.min(10, value));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    record Fact(String key, String content, int importance) {
    }

    /** 抽取得到的三元组草稿;factKey 可选,用于回指支撑它的记忆 */
    record TripleDraft(String subject, String relation, String object, String factKey) {
    }

    /** 一次抽取结果:事实 + 三元组 */
    record Extraction(List<Fact> facts, List<TripleDraft> triples) {
    }

    private record Scored(AgentMemory memory, double score) {
    }
}
