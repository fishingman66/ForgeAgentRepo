package com.forgeagent.agent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.domain.AgentDatasourceExample;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.AgentTool;
import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.bo.AgentDraftBo;
import com.forgeagent.agent.domain.vo.AgentMessageVo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.rag.mapper.AgentKnowledgeMapper;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agent.mapper.AgentMcpServerMapper;
import com.forgeagent.agent.mapper.AgentDatasourceExampleMapper;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agent.mapper.AgentProviderMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import com.forgeagent.agent.mapper.AgentToolMapper;
import com.forgeagent.agent.service.AgentChatAttachmentService;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import com.forgeagent.memory.service.IAgentMemoryService;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.agent.service.TokenUsageRecorder;
import com.forgeagent.rag.service.document.TokenEstimator;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agent.config.AgentChatProperties;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.agentcore.utils.ChatTexts;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 对话上下文装配支持(历史加载 / 团队与工具装配 / 附件组包 / 引用与审计)
 *
 * <p>自 AgentChatServiceImpl 拆出,负责轮次执行前的全部数据装配。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ChatContextAssembler {
    private final com.forgeagent.agentcore.service.ResourceAccessGuard resourceGuard;


    /** Token 用量缓存键前缀(与主类共享的用量缓存键) */
    public static final String TOKEN_SUM_CACHE_PREFIX = "agent:token:sum:";
    public static final long TOKEN_SUM_CACHE_SECONDS = 15;

    private final com.forgeagent.agent.mapper.AgentSessionMapper sessionMapper;
    private final com.forgeagent.agent.mapper.AgentToolMapper toolMapper;
    private final com.forgeagent.agentcore.service.AgentConfigCacheService agentConfigCache;
    private final com.forgeagent.agent.mapper.AgentMcpServerMapper mcpServerMapper;
    private final com.forgeagent.agent.mapper.AgentTokenUsageMapper usageMapper;
    private final com.forgeagent.agent.service.AgentChatAttachmentService attachmentService;
    private final com.forgeagent.agent.config.AgentChatProperties chatProperties;
    private final com.forgeagent.agentcore.service.AuditLogHelper auditLogHelper;

    private final ChatRetrievalSupport retrievalSupport;

    /**
     * 生成引用溯源 JSON(命中分块元数据 + 内容摘要),无命中返回 null
     */
    public String buildCitationsJson(List<KnowledgeSearchHitVo> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchHitVo h = hits.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i + 1)
                .append(",\"fileName\":\"").append(escapeJson(h.getFileName())).append('"')
                .append(",\"chunkIndex\":").append(h.getChunkIndex() == null ? "null" : h.getChunkIndex())
                .append(",\"score\":").append(h.getScore() == null ? "null"
                    : String.format(java.util.Locale.ROOT, "%.4f", h.getScore()));
            // 文件分块带页码/工作表标记时补充定位信息,前端可直接展示"第N页/工作表X"
            Integer pageNo = retrievalSupport.extractPageNo(h.getContent());
            if (pageNo != null) {
                sb.append(",\"pageNo\":").append(pageNo);
            }
            String sheetName = retrievalSupport.extractSheetName(h.getContent());
            if (StringUtils.isNotBlank(sheetName)) {
                sb.append(",\"sheetName\":\"").append(escapeJson(sheetName)).append('"');
            }
            sb.append(",\"snippet\":\"").append(escapeJson(ChatTexts.truncate(h.getContent(), 120))).append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 汇总 Agent 已落库的真实 Token 用量(历史累计,跨会话);
     * 结果短 TTL 缓存,避免每轮对话都做一次全表 SUM
     */
    public Long sumAgentTokens(Long agentId) {
        String cacheKey = TOKEN_SUM_CACHE_PREFIX + agentId;
        try {
            Long cached = RedisUtils.getCacheObject(cacheKey);
            if (cached != null) {
                return cached;
            }
        } catch (Exception ignore) {
            // 缓存不可用时直接查库
        }
        Long total;
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.forgeagent.agent.domain.AgentTokenUsage> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            qw.select("IFNULL(SUM(total_tokens),0)").eq("agent_id", agentId);
            total = usageMapper.selectObjs(qw).stream().findFirst()
                .map(o -> o == null ? 0L : ((Number) o).longValue()).orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
        try {
            RedisUtils.setCacheObject(cacheKey, total, java.time.Duration.ofSeconds(TOKEN_SUM_CACHE_SECONDS));
        } catch (Exception ignore) {
            // 缓存写入失败不影响预算判断
        }
        return total;
    }

    /**
     * 从预加载的会话历史中构造内核 ChatTurn 列表(兼容旧调用方:不裁剪摘要、不按 token 限制)。
     */
    public List<AgentRunner.RunnerContext.ChatTurn> loadHistory(List<AgentMessage> sessionRecent, Long currentUserMsgId) {
        int maxTurns = chatProperties.getHistoryMaxTurns() == null ? 40 : chatProperties.getHistoryMaxTurns();
        return loadHistory(sessionRecent, currentUserMsgId, 0L, Integer.MAX_VALUE, maxTurns).turns();
    }

    /**
     * L2 会话记忆装配(P3 / M1):
     * 过滤掉已被滚动摘要覆盖的旧消息 → 排除本轮用户消息与空内容 →
     * 从最新往回按 token 预算回溯,始终保留最近一条(超长时按预算截断该条)。
     * 固定条数上限仅作兜底,主要裁剪依据是 L0 分配器给出的 historyBudget。
     *
     * @param summaryUptoMsgId 摘要水位(该 id 及之前的消息已被压缩进摘要块)
     * @param historyBudget    L0 分配器给历史的 token 预算
     * @param maxTurns         条数上限(兜底)
     */
    public HistoryResult loadHistory(List<AgentMessage> sessionRecent, Long currentUserMsgId,
                                     long summaryUptoMsgId, int historyBudget, int maxTurns) {
        List<AgentRunner.RunnerContext.ChatTurn> all = sessionRecent.stream()
            .filter(m -> m.getId() != null && m.getId() > summaryUptoMsgId)
            .filter(m -> !m.getId().equals(currentUserMsgId))
            .filter(m -> StringUtils.isNotBlank(m.getContent()))
            .map(m -> new AgentRunner.RunnerContext.ChatTurn(m.getRole(), m.getContent()))
            .toList();
        int limit = maxTurns <= 0 ? 20 : maxTurns;
        int budget = Math.max(0, historyBudget);
        List<AgentRunner.RunnerContext.ChatTurn> kept = new java.util.ArrayList<>();
        int used = 0;
        int dropped = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            AgentRunner.RunnerContext.ChatTurn turn = all.get(i);
            int cost = TokenEstimator.estimate(turn.content()) + 4;
            if (kept.size() >= limit || (!kept.isEmpty() && used + cost > budget)) {
                dropped++;
                continue;
            }
            if (kept.isEmpty() && budget > 0 && cost > budget) {
                turn = new AgentRunner.RunnerContext.ChatTurn(turn.role(),
                    retrievalSupport.truncateToTokens(turn.content(), budget));
                cost = budget;
            }
            kept.add(turn);
            used += cost;
        }
        java.util.Collections.reverse(kept);
        return new HistoryResult(kept, used, dropped, all.size());
    }

    /**
     * 会话历史装配结果(供 Trace 下钻)
     *
     * @param turns        实际注入的历史轮次(升序)
     * @param usedTokens   历史占用 tokens
     * @param droppedTurns 因预算/条数被丢弃的轮次数
     * @param totalTurns   过滤摘要水位后可用的轮次总数
     */
    public record HistoryResult(List<AgentRunner.RunnerContext.ChatTurn> turns, int usedTokens,
                                int droppedTurns, int totalTurns) {
    }

    /**
     * 加载团队协作成员定义(P2 / C2)。
     *
     * <p>四种模式:sequential 顺序接力、parallel 并行分支、reviewer 末棒复核、
     * supervisor 首棒主控规划。角色编排在成员列表层面完成,执行器只需按模式决定是否并行;
     * 成员绑定了自己的工具时允许带工具(并行模式为规避 Toolkit 并发共享,执行时不注入)。</p>
     */
    public List<AgentRunner.RunnerContext.TeamMember> loadTeamMembers(AgentInfo agent) {
        String mode = StringUtils.defaultIfBlank(agent.getTeamMode(), "none");
        if ("none".equals(mode) || StringUtils.isBlank(agent.getTeamIdsStr())
            || !List.of("sequential", "parallel", "reviewer", "supervisor").contains(mode)) {
            return List.of();
        }
        List<Long> teamIds = splitIds(agent.getTeamIdsStr());
        if (teamIds.isEmpty()) {
            return List.of();
        }
        Map<Long, AgentInfo> membersMap = agentConfigCache.getAgentsByIds(teamIds);
        List<AgentRunner.RunnerContext.TeamMember> result = new java.util.ArrayList<>();
        for (Long memberId : teamIds) {
            AgentInfo member = membersMap.get(memberId);
            if (member == null) {
                throw new ServiceException("协作成员不存在或已删除:" + memberId);
            }
            AgentModel memberModel = member.getModelId() == null ? null : agentConfigCache.getModelById(member.getModelId());
            boolean fallback = memberModel == null || StringUtils.isBlank(memberModel.getBaseUrl())
                || StringUtils.isBlank(memberModel.getApiKey()) || StringUtils.isBlank(memberModel.getModelName());
            AgentRunner.RunnerContext.TeamMember.MemberModel modelDef = fallback ? null
                : new AgentRunner.RunnerContext.TeamMember.MemberModel(
                    memberModel.getBaseUrl(), memberModel.getApiKey(), memberModel.getModelName(),
                    memberModel.getTemperature() == null ? null : memberModel.getTemperature().doubleValue(),
                    memberModel.getMaxTokens());
            boolean allowTools = StringUtils.isNotBlank(member.getToolIdsStr());
            result.add(new AgentRunner.RunnerContext.TeamMember(
                member.getId(), member.getName(), member.getSystemPrompt(), modelDef, allowTools));
        }
        if (!result.isEmpty() && "reviewer".equals(mode)) {
            int last = result.size() - 1;
            AgentRunner.RunnerContext.TeamMember m = result.get(last);
            result.set(last, new AgentRunner.RunnerContext.TeamMember(m.agentId(), m.agentName(),
                appendRole(m.sysPrompt(), "你是复核者,请检查前面各棒的结论,指出问题并给出修正意见。"),
                m.model(), m.allowTools()));
        } else if (!result.isEmpty() && "supervisor".equals(mode)) {
            AgentRunner.RunnerContext.TeamMember first = result.getFirst();
            result.add(0, new AgentRunner.RunnerContext.TeamMember(first.agentId(),
                first.agentName() + "(规划)",
                "你是主控规划者,请把用户请求拆成清晰的子任务与验收要点,只输出简短计划。",
                first.model(), false));
        }
        return result;
    }

    private static String appendRole(String base, String role) {
        return StringUtils.isBlank(base) ? role : base + "\n\n" + role;
    }

    /**
     * 团队协作轨迹 extJson 片段(无轨迹返回空串)
     */
    public String teamTraceJson(AgentRunner.RunnerResult result) {
        if (result.teamTrace() == null || result.teamTrace().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(",\"teamTrace\":[");
        for (int i = 0; i < result.teamTrace().size(); i++) {
            AgentRunner.RunnerResult.TeamTraceLine line = result.teamTrace().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"agentId\":").append(line.agentId() == null ? "null" : line.agentId())
                .append(",\"agentName\":\"").append(escapeJson(line.agentName())).append('"')
                .append(",\"role\":\"").append(line.roleLabel()).append('"')
                .append(",\"content\":\"").append(escapeJson(line.content())).append('"')
                .append(",\"totalTokens\":").append(line.totalTokens() == null ? "null" : line.totalTokens())
                .append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * 问数审计:每条 SQL 执行写入审计日志(语句摘要/行数/耗时/结果),失败不阻断会话
     */
    public void recordSqlAudit(AgentInfo agent, List<Map<String, Object>> sqlResults) {
        try {
            for (Map<String, Object> r : sqlResults) {
                boolean success = Boolean.TRUE.equals(r.get("success"));
                String detail = "问数执行 SQL[" + r.get("datasource") + "]: " + r.get("sql")
                    + " 返回 " + r.get("rowCount") + " 行, 耗时 " + r.get("elapsedMs") + "ms"
                    + (success ? "" : ", 失败: " + r.get("error"));
                auditLogHelper.record(agent.getWorkspaceId(), "tool.sql.exec", "datasource",
                    String.valueOf(r.get("datasourceId")), detail);
            }
        } catch (Exception ignore) {
            // 审计失败不影响会话主流程
        }
    }

    public String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    /**
     * 校验会话存在且属于当前用户(超管放行)
     */
    public AgentSession getOwned(Long sessionId) {
        AgentSession exists = sessionMapper.selectById(sessionId);
        if (exists == null) {
            throw new ServiceException("会话不存在");
        }
        if (!LoginHelper.isSuperAdmin() && !exists.getUserId().equals(LoginHelper.getUserId())) {
            throw new ServiceException("无权访问该会话");
        }
        return exists;
    }

    public AgentMessage buildMessage(Long sessionId, String role, String content, String extJson) {
        AgentMessage msg = new AgentMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setExtJson(extJson);
        return msg;
    }

    /**
     * 解析并校验本轮图片附件:严格模式用于发送入口(数量、存在性、工作空间隔离、模型视觉能力),
     * 宽松模式用于重新生成(附件已落库,查不到按无图兜底,不报错)
     */
    public List<AgentChatAttachment> resolveAttachments(List<Long> attachmentIds, AgentInfo agent,
                                                         AgentModel model, boolean tolerant) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = attachmentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        int max = chatProperties.getAttachmentMaxCount() == null ? 4 : chatProperties.getAttachmentMaxCount();
        if (!tolerant && ids.size() > max) {
            throw new ServiceException("单条消息最多 " + max + " 个附件");
        }
        List<AgentChatAttachment> atts = attachmentService.listByIds(ids);
        if (!tolerant && atts.size() < ids.size()) {
            throw new ServiceException("附件不存在或已被清理");
        }
        // 工作空间隔离:附件带工作空间时须与 Agent 一致
        if (!tolerant && agent.getWorkspaceId() != null) {
            for (AgentChatAttachment att : atts) {
                if (att.getWorkspaceId() != null && !att.getWorkspaceId().equals(agent.getWorkspaceId())) {
                    throw new ServiceException("附件不属于当前工作空间");
                }
            }
        }
        // 文件解析状态校验:解析中/失败的文件不允许发送,避免模型拿到空内容
        if (!tolerant) {
            for (AgentChatAttachment att : atts) {
                if (!"file".equals(att.getKind())) {
                    continue;
                }
                if ("0".equals(att.getParseStatus())) {
                    throw new ServiceException("文件「" + att.getOriginalName() + "」正在解析,请稍候再发送");
                }
                if ("2".equals(att.getParseStatus())) {
                    throw new ServiceException("文件「" + att.getOriginalName() + "」解析失败:"
                        + StringUtils.defaultString(att.getFailReason(), "请重新上传"));
                }
            }
        }
        // 模型能力校验:未开启「支持图片输入」的模型仅拒绝图片,文档不受影响
        boolean hasImage = atts.stream().anyMatch(a -> !"file".equals(a.getKind()));
        if (!tolerant && hasImage && (model == null || !"1".equals(model.getSupportVision()))) {
            throw new ServiceException("当前 Agent 绑定的模型未开启「支持图片输入」,请更换模型或移除图片后再发送");
        }
        // 按传入顺序返回(仅保留查到的附件)
        List<AgentChatAttachment> ordered = new java.util.ArrayList<>();
        for (Long id : ids) {
            atts.stream().filter(a -> id.equals(a.getId())).findFirst().ifPresent(ordered::add);
        }
        return ordered;
    }

    /**
     * 读取附件磁盘字节并编码 base64,组装送模图片引用(base64 不带 data URI 前缀)
     */
    public List<AgentRunner.ImageRef> buildImageRefs(List<AgentChatAttachment> atts, boolean tolerant) {
        if (atts == null || atts.isEmpty()) {
            return List.of();
        }
        List<AgentRunner.ImageRef> refs = new java.util.ArrayList<>();
        for (AgentChatAttachment att : atts) {
            try {
                byte[] bytes = attachmentService.readBytes(att);
                refs.add(new AgentRunner.ImageRef(att.getMediaType(),
                    java.util.Base64.getEncoder().encodeToString(bytes)));
            } catch (ServiceException e) {
                if (!tolerant) {
                    throw e;
                }
                // 重新生成场景图片已丢失:按无图处理
            }
        }
        return refs;
    }

    /**
     * 序列化用户消息的图片附件摘要 JSON(仅落库展示,不含原图 base64)
     */
    public String buildAttachmentsJson(List<AgentChatAttachment> atts) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (AgentChatAttachment att : atts) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", att.getId());
            item.put("name", att.getOriginalName());
            item.put("mediaType", att.getMediaType());
            item.put("kind", att.getKind());
            item.put("ext", att.getFileExt());
            item.put("size", att.getFileSize());
            item.put("thumb", att.getThumb());
            item.put("parseStatus", att.getParseStatus());
            item.put("chunkCount", att.getChunkCount());
            item.put("tokenCount", att.getTokenCount());
            item.put("pageCount", att.getPageCount());
            list.add(item);
        }
        return JsonUtils.toJsonString(list);
    }

    /**
     * 从用户消息 attachments JSON 中解析图片附件ID列表(重新生成场景,解析失败按无图)
     */
    public List<Long> parseAttachmentIds(String attachmentsJson) {
        if (StringUtils.isBlank(attachmentsJson)) {
            return List.of();
        }
        try {
            List<Map> items = JsonUtils.parseArray(attachmentsJson, Map.class);
            List<Long> ids = new java.util.ArrayList<>();
            for (Map item : items) {
                Object id = item.get("id");
                // Long 经全局 JsonMapper 序列化为字符串,兼容数字与字符串两种入参
                if (id instanceof Number num) {
                    ids.add(num.longValue());
                } else if (id instanceof String str && !str.isEmpty()) {
                    ids.add(Long.valueOf(str));
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Long> splitIds(String csv) {
        if (StringUtils.isBlank(csv)) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::valueOf).toList();
    }

    /**
     * 加载平台工具定义快照(仅启用状态;http/sandbox/sql 三类)
     */
    public List<ToolAssembler.ToolDef> loadToolDefs(List<Long> toolIds, Long callerWorkspaceId) {
        if (toolIds.isEmpty()) {
            return Collections.emptyList();
        }
        return toolMapper.selectByIds(toolIds).stream()
            .filter(t -> !"1".equals(t.getStatus()))
            .filter(t -> resourceGuard.readableBy(t.getWorkspaceId(), callerWorkspaceId))
            .filter(t -> "http".equals(t.getToolType()) || "sandbox".equals(t.getToolType())
                || "sql".equals(t.getToolType()))
            .map(t -> new ToolAssembler.ToolDef(
                t.getId(), t.getName(), t.getDescription(), t.getToolType(), t.getDatasourceId(),
                t.getHttpMethod(), t.getHttpUrl(), t.getHttpHeaders(), t.getHttpParams(),
                t.getSandboxImage(), t.getSandboxTimeout(), t.getSandboxMemory(), t.getSandboxCpus(),
                t.getApprovalMode(), t.getApprovalTimeout()))
            .toList();
    }

    /**
     * 加载 MCP 服务器定义快照(仅启用状态)
     */
    public List<ToolAssembler.McpDef> loadMcpDefs(List<Long> mcpIds) {
        if (mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        return mcpServerMapper.selectByIds(mcpIds).stream()
            .filter(s -> !"1".equals(s.getStatus()))
            .map(s -> new ToolAssembler.McpDef(
                s.getId(), s.getName(), s.getServerType(), s.getUrl(), s.getHeaders(),
                s.getTimeoutSeconds(), s.getApprovalMode(), s.getApprovalTimeout()))
            .toList();
    }
}