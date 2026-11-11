package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.agent.domain.AgentMarketInstall;
import com.forgeagent.agent.domain.AgentMarketListing;
import com.forgeagent.agent.domain.AgentMarketReview;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentTool;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentMarketInstallBo;
import com.forgeagent.agent.domain.bo.AgentMarketListingBo;
import com.forgeagent.agent.domain.bo.AgentMarketPublishBo;
import com.forgeagent.agent.domain.bo.AgentMarketReviewBo;
import com.forgeagent.agent.domain.vo.AgentInstallCheckVo;
import com.forgeagent.agent.domain.vo.AgentMarketInstallVo;
import com.forgeagent.agent.domain.vo.AgentMarketListingVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.agent.mapper.AgentMarketInstallMapper;
import com.forgeagent.agent.mapper.AgentMarketListingMapper;
import com.forgeagent.agent.mapper.AgentMarketReviewMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.mapper.AgentToolMapper;
import com.forgeagent.agent.mapper.AgentWorkspaceMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agent.service.IAgentMarketService;
import com.forgeagent.agentcore.service.WorkspacePermissionHelper;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 智能体市场 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentMarketServiceImpl implements IAgentMarketService {

    private final AgentMarketListingMapper listingMapper;
    private final AgentMarketInstallMapper installMapper;
    private final AgentMarketReviewMapper reviewMapper;
    private final AgentInfoMapper agentMapper;
    private final AgentModelMapper modelMapper;
    private final AgentToolMapper toolMapper;
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final WorkspacePermissionHelper permissionHelper;
    private final AuditLogHelper auditLogHelper;
    private final AgentConfigCacheService agentConfigCache;

    // ---------- 浏览 ----------

    @Override
    public PageResult<AgentMarketListingVo> browsePage(AgentMarketListingBo bo, PageQuery pageQuery) {
        QueryWrapper<AgentMarketListing> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "1");
        if (StringUtils.isNotBlank(bo.getCategory())) {
            wrapper.eq("category", bo.getCategory());
        }
        if (StringUtils.isNotBlank(bo.getKeyword())) {
            wrapper.and(w -> w.like("name", bo.getKeyword()).or().like("description", bo.getKeyword()));
        }
        applySort(wrapper, bo.getSort());
        Page<AgentMarketListingVo> page = listingMapper.selectVoPage(pageQuery.build(), wrapper);
        fillListingExtras(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentMarketListingVo detail(Long id) {
        AgentMarketListing entity = listingMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("市场条目不存在");
        }
        if (!"1".equals(entity.getStatus()) && !LoginHelper.isSuperAdmin()
            && !entity.getPublisherId().equals(LoginHelper.getUserId())) {
            throw new ServiceException("该条目未上架,无权查看");
        }
        AgentMarketListingVo vo = MapstructUtils.convert(entity, AgentMarketListingVo.class);
        fillListingExtras(List.of(vo));
        return vo;
    }

    // ---------- 我的发布 ----------

    @Override
    public PageResult<AgentMarketListingVo> myPage(AgentMarketListingBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentMarketListing> wrapper = new LambdaQueryWrapper<AgentMarketListing>()
            .eq(AgentMarketListing::getPublisherId, LoginHelper.getUserId())
            .eq(StringUtils.isNotBlank(bo.getStatus()), AgentMarketListing::getStatus, bo.getStatus())
            .orderByDesc(AgentMarketListing::getId);
        Page<AgentMarketListingVo> page = listingMapper.selectVoPage(pageQuery.build(), wrapper);
        fillListingExtras(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publish(AgentMarketPublishBo bo) {
        AgentInfo agent = agentMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        // FR-M5-01: 仅本人创建的且已发布的 Agent 可提交市场审核(超管放行)
        if (!LoginHelper.isSuperAdmin() && !LoginHelper.getUserId().equals(agent.getCreateBy())) {
            throw new ServiceException("仅可发布自己创建的 Agent");
        }
        if (!"1".equals(agent.getStatus())) {
            throw new ServiceException("仅已发布的 Agent 可提交市场审核");
        }
        // 资源域:引用空间私有资源的 Agent 禁止发布到市场(已确认口径)
        AgentModel boundModel = agent.getModelId() == null ? null : modelMapper.selectById(agent.getModelId());
        if (boundModel != null && boundModel.getWorkspaceId() != null) {
            throw new ServiceException("Agent 绑定了空间私有模型,禁止发布到市场");
        }
        List<Long> boundToolIds = splitIds(agent.getToolIdsStr());
        if (!boundToolIds.isEmpty() && toolMapper.selectByIds(boundToolIds).stream()
            .anyMatch(t -> t.getWorkspaceId() != null)) {
            throw new ServiceException("Agent 绑定了空间私有工具,禁止发布到市场");
        }
        // 版本号按源 Agent 递增
        AgentMarketListing latest = listingMapper.selectOne(new LambdaQueryWrapper<AgentMarketListing>()
            .eq(AgentMarketListing::getAgentId, bo.getAgentId())
            .orderByDesc(AgentMarketListing::getVersionNo)
            .last("limit 1"));
        int versionNo = latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;

        AgentMarketListing entity = new AgentMarketListing();
        entity.setAgentId(agent.getId());
        entity.setWorkspaceId(agent.getWorkspaceId());
        entity.setVersionNo(versionNo);
        entity.setName(bo.getName());
        entity.setCategory(bo.getCategory());
        entity.setIcon(bo.getIcon() == null ? "" : bo.getIcon());
        entity.setDescription(bo.getDescription() == null ? "" : bo.getDescription());
        entity.setUsageDoc(bo.getUsageDoc() == null ? "" : bo.getUsageDoc());
        entity.setDepsJson(buildDepsJson(agent));
        entity.setSnapshotJson(buildSnapshotJson(agent));
        entity.setStatus("0");
        entity.setPublisherId(LoginHelper.getUserId());
        entity.setInstallCount(0);
        entity.setRatingSum(0);
        entity.setRatingCount(0);
        listingMapper.insert(entity);
        auditLogHelper.record(agent.getWorkspaceId(), "market.submit", "market_listing", String.valueOf(entity.getId()),
            "提交市场审核[" + bo.getName() + "] v" + versionNo);
        return entity.getId();
    }

    @Override
    public Boolean offline(Long id) {
        AgentMarketListing entity = listingMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("市场条目不存在");
        }
        if (!LoginHelper.isSuperAdmin() && !entity.getPublisherId().equals(LoginHelper.getUserId())) {
            throw new ServiceException("仅发布者本人可下架");
        }
        if (!"1".equals(entity.getStatus())) {
            throw new ServiceException("仅已上架条目可下架");
        }
        AgentMarketListing update = new AgentMarketListing();
        update.setId(id);
        update.setStatus("3");
        update.setReviewReason("发布者主动下架");
        boolean ok = listingMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(entity.getWorkspaceId(), "market.offline", "market_listing", String.valueOf(id),
                "主动下架[" + entity.getName() + "] v" + entity.getVersionNo());
        }
        return ok;
    }

    // ---------- 审核(FR-M5-02) ----------

    @Override
    public PageResult<AgentMarketListingVo> reviewPage(AgentMarketListingBo bo, PageQuery pageQuery) {
        checkSuperAdmin();
        LambdaQueryWrapper<AgentMarketListing> wrapper = new LambdaQueryWrapper<AgentMarketListing>()
            // 审核队列默认仅看待审核
            .eq(AgentMarketListing::getStatus, StringUtils.isBlank(bo.getStatus()) ? "0" : bo.getStatus())
            .orderByAsc(AgentMarketListing::getId);
        Page<AgentMarketListingVo> page = listingMapper.selectVoPage(pageQuery.build(), wrapper);
        fillListingExtras(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public Boolean approve(Long id) {
        checkSuperAdmin();
        AgentMarketListing entity = mustPending(id);
        AgentMarketListing update = new AgentMarketListing();
        update.setId(id);
        update.setStatus("1");
        update.setReviewReason("");
        boolean ok = listingMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(entity.getWorkspaceId(), "market.approve", "market_listing", String.valueOf(id),
                "审核通过[" + entity.getName() + "] v" + entity.getVersionNo());
        }
        return ok;
    }

    @Override
    public Boolean reject(Long id, String reason) {
        checkSuperAdmin();
        AgentMarketListing entity = mustPending(id);
        if (StringUtils.isBlank(reason)) {
            throw new ServiceException("驳回必须填写理由");
        }
        AgentMarketListing update = new AgentMarketListing();
        update.setId(id);
        update.setStatus("2");
        update.setReviewReason(reason);
        boolean ok = listingMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(entity.getWorkspaceId(), "market.reject", "market_listing", String.valueOf(id),
                "审核驳回[" + entity.getName() + "] v" + entity.getVersionNo() + ":" + reason);
        }
        return ok;
    }

    @Override
    public Boolean forceOffline(Long id, String reason) {
        checkSuperAdmin();
        AgentMarketListing entity = listingMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("市场条目不存在");
        }
        if (!"1".equals(entity.getStatus())) {
            throw new ServiceException("仅已上架条目可强制下架");
        }
        AgentMarketListing update = new AgentMarketListing();
        update.setId(id);
        update.setStatus("3");
        update.setReviewReason(StringUtils.isBlank(reason) ? "平台强制下架" : reason);
        boolean ok = listingMapper.updateById(update) > 0;
        if (ok) {
            auditLogHelper.record(entity.getWorkspaceId(), "market.forceoff", "market_listing", String.valueOf(id),
                "强制下架[" + entity.getName() + "] v" + entity.getVersionNo());
        }
        return ok;
    }

    // ---------- 安装(FR-M5-04) ----------

    @Override
    public AgentInstallCheckVo installCheck(AgentMarketInstallBo bo) {
        AgentMarketListing listing = mustOnShelf(bo.getListingId());
        permissionHelper.checkWsMember(bo.getWorkspaceId());
        return doCheck(listing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long installConfirm(AgentMarketInstallBo bo) {
        AgentMarketListing listing = mustOnShelf(bo.getListingId());
        permissionHelper.checkWsMember(bo.getWorkspaceId());
        AgentWorkspace ws = workspaceMapper.selectById(bo.getWorkspaceId());
        if (ws == null) {
            throw new ServiceException("目标工作空间不存在");
        }
        AgentInstallCheckVo check = doCheck(listing);
        if (!Boolean.TRUE.equals(check.getInstallable())) {
            throw new ServiceException("模型依赖不满足,无法安装:" + check.getModelMessage());
        }
        Map<String, Object> snapshot = JsonUtils.parseMap(listing.getSnapshotJson());
        // 生成 Agent 副本(来源:市场安装),缺失的工具/知识库跳过并记录
        AgentInfo copy = new AgentInfo();
        copy.setWorkspaceId(bo.getWorkspaceId());
        copy.setName(dedupeName(bo.getWorkspaceId(), listing.getName()));
        copy.setDescription(strVal(snapshot.get("description")));
        copy.setSystemPrompt(strVal(snapshot.get("systemPrompt")));
        copy.setWelcomeMsg(strVal(snapshot.get("welcomeMsg")));
        copy.setModelId(longVal(snapshot.get("modelId")));
        copy.setTemperature(snapshot.get("temperature") == null ? null
            : new java.math.BigDecimal(strVal(snapshot.get("temperature"))));
        copy.setMaxTokens(intVal(snapshot.get("maxTokens"), 4096));
        List<Long> toolIds = resolveIds(check.getTools());
        List<Long> knowledgeIds = resolveIds(check.getKnowledges());
        copy.setToolIdsStr(toolIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        copy.setKnowledgeIdsStr(knowledgeIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        copy.setStatus("0");
        agentMapper.insert(copy);

        Map<String, Object> bindings = new HashMap<>();
        bindings.put("modelId", copy.getModelId());
        bindings.put("toolIds", toolIds);
        bindings.put("knowledgeIds", knowledgeIds);
        bindings.put("skippedTools", check.getTools().stream().filter(t -> !Boolean.TRUE.equals(t.getFound()))
            .map(AgentInstallCheckVo.DepItem::getName).toList());
        bindings.put("skippedKnowledges", check.getKnowledges().stream().filter(k -> !Boolean.TRUE.equals(k.getFound()))
            .map(AgentInstallCheckVo.DepItem::getName).toList());

        AgentMarketInstall install = new AgentMarketInstall();
        install.setListingId(listing.getId());
        install.setWorkspaceId(bo.getWorkspaceId());
        install.setAgentCopyId(copy.getId());
        install.setVersionNo(listing.getVersionNo());
        install.setBindingsJson(JsonUtils.toJsonString(bindings));
        install.setUserId(LoginHelper.getUserId());
        installMapper.insert(install);

        AgentMarketListing countUpdate = new AgentMarketListing();
        countUpdate.setId(listing.getId());
        countUpdate.setInstallCount((listing.getInstallCount() == null ? 0 : listing.getInstallCount()) + 1);
        listingMapper.updateById(countUpdate);

        auditLogHelper.record(bo.getWorkspaceId(), "market.install", "market_listing", String.valueOf(listing.getId()),
            "安装[" + listing.getName() + "] v" + listing.getVersionNo() + " → 副本Agent#" + copy.getId());
        return copy.getId();
    }

    // ---------- 升级(FR-M5-05) ----------

    @Override
    public List<AgentMarketInstallVo> myInstalls(Long workspaceId) {
        List<Long> scope;
        if (workspaceId != null) {
            permissionHelper.checkWsMember(workspaceId);
            scope = List.of(workspaceId);
        } else if (LoginHelper.isSuperAdmin()) {
            scope = null;
        } else {
            scope = memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                    .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId()))
                .stream().map(AgentWorkspaceMember::getWorkspaceId).distinct().toList();
            if (scope.isEmpty()) {
                return Collections.emptyList();
            }
        }
        List<AgentMarketInstall> installs = installMapper.selectList(new LambdaQueryWrapper<AgentMarketInstall>()
            .in(scope != null, AgentMarketInstall::getWorkspaceId, scope)
            .orderByDesc(AgentMarketInstall::getId));
        if (installs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, AgentMarketListing> listingMap = listingMapper.selectByIds(
                installs.stream().map(AgentMarketInstall::getListingId).distinct().toList())
            .stream().collect(Collectors.toMap(AgentMarketListing::getId, Function.identity()));
        Map<Long, String> copyNames = agentMapper.selectByIds(
                installs.stream().map(AgentMarketInstall::getAgentCopyId).distinct().toList())
            .stream().collect(Collectors.toMap(AgentInfo::getId, AgentInfo::getName, (a, b) -> a));
        Map<Long, String> wsNames = workspaceMapper.selectByIds(
                installs.stream().map(AgentMarketInstall::getWorkspaceId).distinct().toList())
            .stream().collect(Collectors.toMap(AgentWorkspace::getId, AgentWorkspace::getName, (a, b) -> a));
        List<Long> ratedInstallIds = reviewMapper.selectList(new LambdaQueryWrapper<AgentMarketReview>()
                .in(AgentMarketReview::getInstallId, installs.stream().map(AgentMarketInstall::getId).toList()))
            .stream().map(AgentMarketReview::getInstallId).toList();
        // 同 Agent 最新上架版本(跨版本条目)
        List<Long> agentIds = listingMap.values().stream().map(AgentMarketListing::getAgentId).distinct().toList();
        Map<Long, Integer> latestOnShelf = agentIds.isEmpty() ? Map.of() :
            listingMapper.selectList(new LambdaQueryWrapper<AgentMarketListing>()
                    .in(AgentMarketListing::getAgentId, agentIds)
                    .eq(AgentMarketListing::getStatus, "1"))
                .stream().collect(Collectors.toMap(AgentMarketListing::getAgentId, AgentMarketListing::getVersionNo, Math::max));

        List<AgentMarketInstallVo> result = new ArrayList<>();
        for (AgentMarketInstall install : installs) {
            AgentMarketInstallVo vo = MapstructUtils.convert(install, AgentMarketInstallVo.class);
            AgentMarketListing listing = listingMap.get(install.getListingId());
            if (listing != null) {
                vo.setListingName(listing.getName());
                vo.setListingIcon(listing.getIcon());
                // 最新上架版本号(无上架版本时视为当前安装版本,即不可升级)
                vo.setLatestVersionNo(latestOnShelf.getOrDefault(listing.getAgentId(), install.getVersionNo()));
            }
            vo.setWorkspaceName(wsNames.get(install.getWorkspaceId()));
            vo.setAgentCopyName(copyNames.get(install.getAgentCopyId()));
            vo.setRated(ratedInstallIds.contains(install.getId()));
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean upgrade(Long installId) {
        AgentMarketInstall install = installMapper.selectById(installId);
        if (install == null) {
            throw new ServiceException("安装记录不存在");
        }
        permissionHelper.checkWsDeveloper(install.getWorkspaceId());
        AgentMarketListing installed = listingMapper.selectById(install.getListingId());
        if (installed == null) {
            throw new ServiceException("市场条目不存在");
        }
        // 定位同 Agent 最新上架版本(新版本是新条目行)
        AgentMarketListing listing = listingMapper.selectOne(new LambdaQueryWrapper<AgentMarketListing>()
            .eq(AgentMarketListing::getAgentId, installed.getAgentId())
            .eq(AgentMarketListing::getStatus, "1")
            .orderByDesc(AgentMarketListing::getVersionNo)
            .last("limit 1"));
        if (listing == null) {
            throw new ServiceException("市场条目已下架,无法升级");
        }
        if (listing.getVersionNo() <= install.getVersionNo()) {
            throw new ServiceException("当前已是最新版本");
        }
        AgentInfo copy = agentMapper.selectById(install.getAgentCopyId());
        if (copy == null) {
            throw new ServiceException("安装的 Agent 副本已被删除,无法升级");
        }
        // 留存旧快照支持一键回退
        Map<String, Object> prev = snapshotOf(copy);
        Map<String, Object> snapshot = JsonUtils.parseMap(listing.getSnapshotJson());
        // 升级仅更新配置内容,保留现有资源绑定(model/工具/知识库不动)
        copy.setDescription(strVal(snapshot.get("description")));
        copy.setSystemPrompt(strVal(snapshot.get("systemPrompt")));
        copy.setWelcomeMsg(strVal(snapshot.get("welcomeMsg")));
        if (snapshot.get("temperature") != null) {
            copy.setTemperature(new java.math.BigDecimal(strVal(snapshot.get("temperature"))));
        }
        if (snapshot.get("maxTokens") != null) {
            copy.setMaxTokens(intVal(snapshot.get("maxTokens"), copy.getMaxTokens()));
        }
        agentMapper.updateById(copy);
        // 市场安装直接改写 agent_info,同步清热点配置缓存
        agentConfigCache.evictAgent(copy.getId());

        AgentMarketInstall update = new AgentMarketInstall();
        update.setId(installId);
        update.setListingId(listing.getId());
        update.setVersionNo(listing.getVersionNo());
        update.setPrevSnapshotJson(JsonUtils.toJsonString(prev));
        installMapper.updateById(update);

        auditLogHelper.record(install.getWorkspaceId(), "market.upgrade", "market_listing", String.valueOf(listing.getId()),
            "升级[" + listing.getName() + "] v" + install.getVersionNo() + "→v" + listing.getVersionNo());
        return true;
    }

    // ---------- 评分(FR-M5-06) ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean rate(AgentMarketReviewBo bo) {
        AgentMarketInstall install = installMapper.selectById(bo.getInstallId());
        if (install == null) {
            throw new ServiceException("安装记录不存在");
        }
        if (!LoginHelper.getUserId().equals(install.getUserId())) {
            throw new ServiceException("仅安装人可对该条目评分");
        }
        AgentMarketListing listing = listingMapper.selectById(install.getListingId());
        if (listing == null) {
            throw new ServiceException("市场条目不存在");
        }
        AgentMarketReview existing = reviewMapper.selectOne(new LambdaQueryWrapper<AgentMarketReview>()
            .eq(AgentMarketReview::getInstallId, bo.getInstallId())
            .last("limit 1"));
        int sumDelta;
        int countDelta = 0;
        if (existing == null) {
            AgentMarketReview review = new AgentMarketReview();
            review.setListingId(install.getListingId());
            review.setInstallId(bo.getInstallId());
            review.setUserId(LoginHelper.getUserId());
            review.setStars(bo.getStars());
            review.setComment(bo.getComment() == null ? "" : bo.getComment());
            reviewMapper.insert(review);
            sumDelta = bo.getStars();
            countDelta = 1;
        } else {
            // 改评:仅调整星数合计
            sumDelta = bo.getStars() - (existing.getStars() == null ? 0 : existing.getStars());
            AgentMarketReview update = new AgentMarketReview();
            update.setId(existing.getId());
            update.setStars(bo.getStars());
            update.setComment(bo.getComment() == null ? "" : bo.getComment());
            reviewMapper.updateById(update);
        }
        AgentMarketListing countUpdate = new AgentMarketListing();
        countUpdate.setId(listing.getId());
        countUpdate.setRatingSum((listing.getRatingSum() == null ? 0 : listing.getRatingSum()) + sumDelta);
        countUpdate.setRatingCount((listing.getRatingCount() == null ? 0 : listing.getRatingCount()) + countDelta);
        listingMapper.updateById(countUpdate);
        return true;
    }

    // ---------- 私有方法 ----------

    private void applySort(QueryWrapper<AgentMarketListing> wrapper, String sort) {
        if ("install".equals(sort)) {
            wrapper.orderByDesc("install_count").orderByDesc("id");
        } else if ("rating".equals(sort)) {
            wrapper.orderByDesc("rating_sum / greatest(rating_count, 1)").orderByDesc("id");
        } else {
            wrapper.orderByDesc("id");
        }
    }

    private AgentMarketListing mustOnShelf(Long listingId) {
        AgentMarketListing listing = listingMapper.selectById(listingId);
        if (listing == null) {
            throw new ServiceException("市场条目不存在");
        }
        if (!"1".equals(listing.getStatus())) {
            throw new ServiceException("该条目未处于上架状态");
        }
        return listing;
    }

    private AgentMarketListing mustPending(Long id) {
        AgentMarketListing entity = listingMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("市场条目不存在");
        }
        if (!"0".equals(entity.getStatus())) {
            throw new ServiceException("该条目不在待审核状态");
        }
        return entity;
    }

    private void checkSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new ServiceException("仅平台管理员可执行该操作");
        }
    }

    /**
     * 依赖声明摘要(发布时从 Agent 配置汇总)
     */
    private String buildDepsJson(AgentInfo agent) {
        Map<String, Object> deps = new HashMap<>();
        AgentModel model = agent.getModelId() == null ? null : modelMapper.selectById(agent.getModelId());
        deps.put("modelName", model == null ? "" : model.getModelName());
        deps.put("provider", model == null ? "" : model.getProvider());
        deps.put("toolNames", loadTools(agent.getToolIdsStr()).stream().map(AgentTool::getName).toList());
        deps.put("knowledgeNames", loadKnowledges(agent.getKnowledgeIdsStr()).stream().map(AgentKnowledge::getName).toList());
        return JsonUtils.toJsonString(deps);
    }

    /**
     * 版本快照(不可变)
     */
    private String buildSnapshotJson(AgentInfo agent) {
        return JsonUtils.toJsonString(snapshotOf(agent));
    }

    private Map<String, Object> snapshotOf(AgentInfo agent) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", agent.getName());
        snapshot.put("description", agent.getDescription() == null ? "" : agent.getDescription());
        snapshot.put("systemPrompt", agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt());
        snapshot.put("welcomeMsg", agent.getWelcomeMsg() == null ? "" : agent.getWelcomeMsg());
        snapshot.put("modelId", agent.getModelId());
        snapshot.put("temperature", agent.getTemperature() == null ? "0.70" : agent.getTemperature().toPlainString());
        snapshot.put("maxTokens", agent.getMaxTokens() == null ? 4096 : agent.getMaxTokens());
        snapshot.put("toolIds", splitIds(agent.getToolIdsStr()));
        snapshot.put("knowledgeIds", splitIds(agent.getKnowledgeIdsStr()));
        return snapshot;
    }

    /**
     * 依赖检查:模型/工具/知识库均为平台级资源,校验存在性与可用性
     */
    private AgentInstallCheckVo doCheck(AgentMarketListing listing) {
        Map<String, Object> snapshot = JsonUtils.parseMap(listing.getSnapshotJson());
        AgentInstallCheckVo vo = new AgentInstallCheckVo();
        Long modelId = longVal(snapshot.get("modelId"));
        AgentModel model = modelId == null ? null : modelMapper.selectById(modelId);
        if (model == null) {
            vo.setModelOk(false);
            vo.setModelName("");
            vo.setModelMessage("绑定的模型配置已不存在");
        } else if (!"0".equals(model.getStatus())) {
            vo.setModelOk(false);
            vo.setModelName(model.getName());
            vo.setModelMessage("模型配置[" + model.getName() + "]已停用");
        } else {
            vo.setModelOk(true);
            vo.setModelName(model.getName());
            vo.setModelMessage("模型配置[" + model.getName() + "]可用");
        }
        vo.setTools(new ArrayList<>());
        for (Long toolId : longList(snapshot.get("toolIds"))) {
            vo.getTools().add(buildToolItem(toolId));
        }
        vo.setKnowledges(new ArrayList<>());
        for (Long kid : longList(snapshot.get("knowledgeIds"))) {
            vo.getKnowledges().add(buildKnowledgeItem(kid));
        }
        vo.setInstallable(vo.getModelOk());
        return vo;
    }

    private AgentInstallCheckVo.DepItem buildToolItem(Long toolId) {
        AgentTool tool = toolId == null ? null : toolMapper.selectById(toolId);
        AgentInstallCheckVo.DepItem item = new AgentInstallCheckVo.DepItem();
        item.setId(toolId);
        item.setFound(tool != null);
        item.setName(tool == null ? "工具#" + toolId : tool.getName());
        item.setEnabled(tool != null && "0".equals(tool.getStatus()));
        return item;
    }

    private AgentInstallCheckVo.DepItem buildKnowledgeItem(Long kid) {
        AgentKnowledge knowledge = kid == null ? null : knowledgeMapper.selectById(kid);
        AgentInstallCheckVo.DepItem item = new AgentInstallCheckVo.DepItem();
        item.setId(kid);
        item.setFound(knowledge != null);
        item.setName(knowledge == null ? "知识库#" + kid : knowledge.getName());
        item.setEnabled(knowledge != null && "0".equals(knowledge.getStatus()));
        return item;
    }

    /**
     * 解析出可用(存在且启用)的资源ID
     */
    private List<Long> resolveIds(List<AgentInstallCheckVo.DepItem> items) {
        return items.stream()
            .filter(i -> Boolean.TRUE.equals(i.getFound()) && Boolean.TRUE.equals(i.getEnabled()))
            .map(AgentInstallCheckVo.DepItem::getId).toList();
    }

    /**
     * 空间内名称去重:冲突时追加(副本)/(副本N)
     */
    private String dedupeName(Long workspaceId, String baseName) {
        String candidate = baseName;
        for (int i = 1; i <= 20; i++) {
            Long count = agentMapper.selectCount(new LambdaQueryWrapper<AgentInfo>()
                .eq(AgentInfo::getWorkspaceId, workspaceId)
                .eq(AgentInfo::getName, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
            candidate = baseName + "(副本" + (i == 1 ? "" : String.valueOf(i)) + ")";
        }
        return baseName + "(副本" + System.currentTimeMillis() % 10000 + ")";
    }

    private List<AgentTool> loadTools(String csv) {
        List<Long> ids = splitIds(csv);
        return ids.isEmpty() ? Collections.emptyList() : toolMapper.selectByIds(ids);
    }

    private List<AgentKnowledge> loadKnowledges(String csv) {
        List<Long> ids = splitIds(csv);
        return ids.isEmpty() ? Collections.emptyList() : knowledgeMapper.selectByIds(ids);
    }

    private void fillListingExtras(List<AgentMarketListingVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> publisherIds = list.stream().map(AgentMarketListingVo::getPublisherId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> publisherNames = new HashMap<>();
        if (!publisherIds.isEmpty()) {
            for (Map<String, Object> row : listingMapper.selectPublisherNames(publisherIds)) {
                Object idObj = row.get("id");
                if (idObj != null) {
                    publisherNames.put(Long.valueOf(idObj.toString()), Objects.toString(row.get("name"), ""));
                }
            }
        }
        list.forEach(vo -> {
            vo.setPublisherName(publisherNames.getOrDefault(vo.getPublisherId(), ""));
            int sum = vo.getRatingSum() == null ? 0 : vo.getRatingSum();
            int count = vo.getRatingCount() == null ? 0 : vo.getRatingCount();
            vo.setRatingAvg(count == 0 ? 0D : Math.round(sum * 10D / count) / 10D);
        });
    }

    private List<Long> splitIds(String csv) {
        if (StringUtils.isBlank(csv)) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(csv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Long> longList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(v -> Long.valueOf(v.toString())).toList();
        }
        return Collections.emptyList();
    }

    private String strVal(Object value) {
        return value == null ? "" : value.toString();
    }

    private Long longVal(Object value) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return null;
        }
        return Long.valueOf(value.toString());
    }

    private Integer intVal(Object value, Integer fallback) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return fallback;
        }
        return Integer.valueOf(value.toString());
    }
}
