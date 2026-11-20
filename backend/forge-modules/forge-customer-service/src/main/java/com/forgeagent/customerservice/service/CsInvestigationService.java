package com.forgeagent.customerservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.runner.SqlQueryService;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.callcenter.common.CsPiiMasker;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.customerservice.domain.CsInvestigation;
import com.forgeagent.customerservice.domain.CsInvestigationEvidence;
import com.forgeagent.customerservice.domain.CsTicket;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import com.forgeagent.customerservice.domain.bo.CsInvestigationConfirmBo;
import com.forgeagent.customerservice.domain.vo.CsInvestigationVo;
import com.forgeagent.customerservice.mapper.CsInvestigationEvidenceMapper;
import com.forgeagent.customerservice.mapper.CsInvestigationMapper;
import com.forgeagent.customerservice.mapper.CsTicketMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * B4 AI 工单根因调查(RCA)编排。
 *
 * <p>链路:工单上下文 → 模型提假设(附只读 SQL)→ {@link SqlQueryService} 逐条取数
 * (表白名单 + 只读 + 行数/超时熔断)→ 证据落链 → 模型出报告 → 坐席人工确认后回填工单。</p>
 *
 * <p>安全口径:数据源必须配置非空 table_whitelist(否则等于全库可查,直接拒绝);
 * 报告与证据摘要落库前脱敏,但订单号/ID/编码类列保留原值以免证据失真;
 * 报告正文只进调查表,工单事件只写"报告 #id + 一句话",避免 note 字段截断丢失。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsInvestigationService {

    private static final int MAX_HYPOTHESES = 3;
    private static final int MAX_ROWS_FOR_DIGEST = 20;
    private static final int MAX_DIGEST_CHARS = 2000;
    private static final int MAX_REPORT_CHARS = 20000;
    private static final int MAX_CONTEXT_CHARS = 6000;

    private static final String PROPOSE_SYSTEM = """
        你是资深客服工单根因分析工程师。根据工单上下文与可用表结构,提出最多 3 条可验证的假设,
        每条假设给一条只读 SQL 用于取数验证。只输出 JSON 数组,不要任何解释或代码块:
        [{"title":"假设简述","rationale":"为什么怀疑","sql":"SELECT ... LIMIT 50"}]
        硬性约束:SQL 只能是单条 SELECT;只能使用给定表结构中的表;必须带 LIMIT 且不超过 100;
        禁止写操作/DDL/注释/多语句;不要编造不存在的表名或字段。
        """;

    private static final String REPORT_SYSTEM = """
        你是客服工单根因分析工程师。根据假设与取数证据写一份中文根因分析报告,Markdown 格式:
        ## 结论
        ## 证据链
        ## 处置建议
        只依据给定证据推导,证据不足要明确写出不确定性,禁止编造数据。
        """;

    private final CsInvestigationMapper investigationMapper;
    private final CsInvestigationEvidenceMapper evidenceMapper;
    private final CsTicketMapper ticketMapper;
    private final CsTicketService ticketService;
    private final AgentDatasourceMapper datasourceMapper;
    private final AgentConfigCacheService agentConfigCache;
    private final SqlQueryService sqlQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------ 发起

    /**
     * 发起一次根因调查:同步落库 + 异步执行,立即返回调查 ID 供前端轮询。
     */
    public Long start(Long ticketId, Long datasourceId, Long workspaceId, Long operatorId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new ServiceException("请先切换到具体工作空间");
        }
        CsTicket ticket = ticketMapper.selectById(ticketId);
        // 跨空间统一话术:不区分"不存在"与"不是你的",避免存在性探测
        if (ticket == null || !workspaceId.equals(ticket.getWorkspaceId())) {
            throw new ServiceException("工单不存在");
        }
        AgentDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new ServiceException("数据源不存在");
        }
        if (ds.getWorkspaceId() != null && !ds.getWorkspaceId().equals(workspaceId)) {
            throw new ServiceException("数据源不属于当前工作空间");
        }
        if (StringUtils.isBlank(ds.getTableWhitelist())) {
            throw new ServiceException("该数据源未配置表白名单,不允许用于根因调查");
        }
        AgentModel model = resolveModel(ticket.getAgentId());

        CsInvestigation inv = new CsInvestigation();
        inv.setWorkspaceId(workspaceId);
        inv.setTicketId(ticketId);
        inv.setConversationId(ticket.getConversationId());
        inv.setDatasourceId(datasourceId);
        inv.setAgentId(ticket.getAgentId());
        inv.setTemplateCode("RCA_STANDARD");
        inv.setStatus("RUNNING");
        inv.setCreatedBy(operatorId);
        inv.setCreateTime(new Date());
        inv.setUpdateTime(new Date());
        investigationMapper.insert(inv);

        Long invId = inv.getId();
        String context = buildTicketContext(ticket);
        // 异步执行:不依赖登录态(所有归属信息已在入参),避免 supplyAsync 丢 Sa-Token 的坑
        CompletableFuture.runAsync(() -> run(invId, ticket, ds, model, context, operatorId));
        return invId;
    }

    // ------------------------------------------------------------ 执行

    private void run(Long invId, CsTicket ticket, AgentDatasource ds, AgentModel model,
                     String context, Long operatorId) {
        CsInvestigation inv = null;
        try {
            inv = investigationMapper.selectById(invId);
            if (inv == null) {
                return;
            }
            List<Map<String, Object>> hypotheses = propose(model, ds, context);
            if (hypotheses.isEmpty()) {
                fail(inv, "模型未给出可执行的调查假设");
                return;
            }
            inv.setHypothesesJson(objectMapper.writeValueAsString(hypotheses));
            investigationMapper.updateById(inv);

            StringBuilder evidenceText = new StringBuilder();
            int seq = 0;
            for (Map<String, Object> hypothesis : hypotheses) {
                seq++;
                CsInvestigationEvidence evidence = new CsInvestigationEvidence();
                evidence.setInvestigationId(invId);
                evidence.setSeq(seq);
                evidence.setEvidenceType("SQL");
                evidence.setHypothesisTitle(truncate(text(hypothesis.get("title")), 200));
                String sql = text(hypothesis.get("sql"));
                evidence.setQueryText(truncate(sql, 2000));
                evidence.setSuccess(0);
                evidence.setCreateTime(new Date());
                String block;
                if (StringUtils.isBlank(sql)) {
                    evidence.setErrorMessage("假设未给出可执行 SQL");
                    block = "- 证据" + seq + "[" + evidence.getHypothesisTitle() + "] 未提供 SQL";
                } else {
                    try {
                        SqlQueryService.SqlResult result = sqlQueryService.execute(ds, sql);
                        String digest = digest(result);
                        evidence.setSuccess(1);
                        evidence.setRowCount(result.rowCount());
                        evidence.setElapsedMs((int) result.elapsedMs());
                        evidence.setResultDigest(digest);
                        block = "- 证据" + seq + "[" + evidence.getHypothesisTitle() + "] 行数="
                            + result.rowCount() + "\n" + digest;
                    } catch (Exception e) {
                        String msg = rootMessage(e);
                        evidence.setErrorMessage(truncate(msg, 500));
                        block = "- 证据" + seq + "[" + evidence.getHypothesisTitle() + "] 取数失败:" + msg;
                    }
                }
                evidenceMapper.insert(evidence);
                evidenceText.append(block).append('\n');
            }

            String report = report(model, context, evidenceText.toString());
            if (StringUtils.isBlank(report)) {
                fail(inv, "模型未生成报告");
                return;
            }
            inv.setReportMd(truncate(CsPiiMasker.mask(report), MAX_REPORT_CHARS));
            inv.setStatus("SUCCEEDED");
            inv.setUpdateTime(new Date());
            investigationMapper.updateById(inv);
            try {
                ticketService.addNote(ticket.getId(), operatorId,
                    "AI 根因调查完成(调查#" + invId + "),请坐席确认结论后回填工单");
            } catch (Exception e) {
                log.warn("[RCA] 调查报告完成但工单留痕失败 inv={}: {}", invId, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[RCA] 调查 {} 执行失败: {}", invId, e.getMessage());
            if (inv != null) {
                fail(inv, rootMessage(e));
            }
        }
    }

    private List<Map<String, Object>> propose(AgentModel model, AgentDatasource ds, String context) throws Exception {
        String schema = truncate(StringUtils.defaultIfBlank(ds.getSchemaSnapshot(), "未提供表结构"), 6000);
        String user = "工单上下文:\n" + context + "\n\n可用表结构:\n" + schema;
        String raw = callModel(model, PROPOSE_SYSTEM, user, 60);
        return parseHypotheses(raw);
    }

    private String report(AgentModel model, String context, String evidenceText) throws Exception {
        String user = "工单上下文:\n" + context + "\n\n取数证据:\n" + truncate(evidenceText, 8000);
        return callModel(model, REPORT_SYSTEM, user, 90);
    }

    private String callModel(AgentModel model, String systemPrompt, String userPrompt, int timeoutSeconds) {
        String text = UtilityAgents.build("cs-rca", systemPrompt, model)
            .call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                .textContent(truncate(userPrompt, 12000)).build()))
            .block(Duration.ofSeconds(timeoutSeconds))
            .getTextContent();
        if (StringUtils.isBlank(text)) {
            throw new ServiceException("模型返回为空");
        }
        return text;
    }

    private List<Map<String, Object>> parseHypotheses(String raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (StringUtils.isBlank(raw)) {
            return result;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(raw.substring(start, end + 1),
                new TypeReference<List<Map<String, Object>>>() {
                });
            for (Map<String, Object> item : parsed) {
                if (result.size() >= MAX_HYPOTHESES) {
                    break;
                }
                if (StringUtils.isNotBlank(text(item.get("title")))) {
                    result.add(item);
                }
            }
        } catch (Exception e) {
            log.debug("[RCA] 假设 JSON 解析失败: {}", e.getMessage());
        }
        return result;
    }

    /** 证据摘要:结构化列保留标识值,其余走 PII 脱敏(避免订单号被当银行卡打码) */
    private String digest(SqlQueryService.SqlResult result) {
        StringBuilder sb = new StringBuilder();
        List<String> columns = result.columns();
        sb.append(String.join(" | ", columns)).append('\n');
        int rowLimit = Math.min(result.rows().size(), MAX_ROWS_FOR_DIGEST);
        for (int i = 0; i < rowLimit; i++) {
            List<Object> row = result.rows().get(i);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < columns.size(); c++) {
                Object value = c < row.size() ? row.get(c) : null;
                String cell = truncate(value == null ? "" : String.valueOf(value), 60);
                if (!isIdentifierColumn(columns.get(c))) {
                    cell = CsPiiMasker.mask(cell);
                }
                cells.add(cell);
            }
            sb.append(String.join(" | ", cells)).append('\n');
        }
        if (result.truncated()) {
            sb.append("(结果已按行数上限截断)\n");
        }
        return truncate(sb.toString(), MAX_DIGEST_CHARS);
    }

    private boolean isIdentifierColumn(String column) {
        String c = column == null ? "" : column.toLowerCase();
        return c.equals("id") || c.endsWith("_id") || c.endsWith("_no") || c.contains("order")
            || c.contains("code") || c.contains("ticket");
    }

    private String buildTicketContext(CsTicket ticket) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单号:").append(StringUtils.defaultIfBlank(ticket.getTicketNo(), "-")).append('\n');
        sb.append("标题:").append(CsPiiMasker.mask(StringUtils.defaultIfBlank(ticket.getTitle(), "-"))).append('\n');
        sb.append("分类:").append(StringUtils.defaultIfBlank(ticket.getCategory(), "-")).append('\n');
        sb.append("优先级:").append(StringUtils.defaultIfBlank(ticket.getPriority(), "-")).append('\n');
        sb.append("状态:").append(StringUtils.defaultIfBlank(ticket.getStatus(), "-")).append('\n');
        sb.append("转人工原因:")
            .append(CsPiiMasker.mask(StringUtils.defaultIfBlank(ticket.getHandoffReason(), "-"))).append('\n');
        if (StringUtils.isNotBlank(ticket.getResolveNote())) {
            sb.append("处理备注:").append(CsPiiMasker.mask(ticket.getResolveNote())).append('\n');
        }
        try {
            List<CsTicketEvent> events = ticketService.events(ticket.getId());
            int from = Math.max(0, events.size() - 5);
            for (CsTicketEvent e : events.subList(from, events.size())) {
                sb.append("- ").append(e.getEventType()).append(':')
                    .append(CsPiiMasker.mask(StringUtils.defaultIfBlank(e.getNote(), ""))).append('\n');
            }
        } catch (Exception ignore) {
            // 事件缺失不影响调查主线
        }
        return truncate(sb.toString(), MAX_CONTEXT_CHARS);
    }

    // ------------------------------------------------------------ 查询与确认

    public CsInvestigationVo detail(Long id, Long workspaceId) {
        CsInvestigation inv = requireInv(id, workspaceId);
        CsInvestigationVo vo = toVo(inv);
        List<CsInvestigationEvidence> evidences = evidenceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsInvestigationEvidence>()
                .eq(CsInvestigationEvidence::getInvestigationId, id)
                .orderByAsc(CsInvestigationEvidence::getSeq));
        vo.setEvidences(evidences.stream().map(this::toEvidence).toList());
        return vo;
    }

    public List<CsInvestigationVo> listByTicket(Long ticketId, Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new ServiceException("请先切换到具体工作空间");
        }
        CsTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || !workspaceId.equals(ticket.getWorkspaceId())) {
            throw new ServiceException("工单不存在");
        }
        return investigationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsInvestigation>()
                    .eq(CsInvestigation::getTicketId, ticketId)
                    .orderByDesc(CsInvestigation::getId))
            .stream().map(this::toVo).toList();
    }

    /** 坐席确认:采纳则回填工单留痕,驳回同样留痕;报告正文不写入事件(note 仅 1000 字) */
    @Transactional(rollbackFor = Exception.class)
    public CsInvestigationVo confirm(Long id, Long workspaceId, Long operatorId, CsInvestigationConfirmBo bo) {
        CsInvestigation inv = requireInv(id, workspaceId);
        if (!"SUCCEEDED".equals(inv.getStatus())) {
            throw new ServiceException("该调查尚未完成或已处理,不能重复确认");
        }
        boolean approved = Boolean.TRUE.equals(bo.getApproved());
        inv.setStatus(approved ? "CONFIRMED" : "REJECTED");
        inv.setConfirmedBy(operatorId);
        inv.setConfirmedAt(new Date());
        inv.setUpdateTime(new Date());
        investigationMapper.updateById(inv);
        String extra = StringUtils.isBlank(bo.getNote()) ? "" : "," + truncate(bo.getNote(), 200);
        ticketService.addNote(inv.getTicketId(), operatorId,
            (approved ? "已采纳 AI 根因调查#%d" : "已驳回 AI 根因调查#%d").formatted(id) + extra);
        return toVo(inv);
    }

    private CsInvestigation requireInv(Long id, Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new ServiceException("请先切换到具体工作空间");
        }
        CsInvestigation inv = investigationMapper.selectById(id);
        if (inv == null || !workspaceId.equals(inv.getWorkspaceId())) {
            throw new ServiceException("调查记录不存在");
        }
        return inv;
    }

    private AgentModel resolveModel(Long agentId) {
        if (agentId == null) {
            throw new ServiceException("工单未关联 Agent,无法执行 AI 调查");
        }
        AgentInfo agent = agentConfigCache.getAgentById(agentId);
        if (agent == null || agent.getModelId() == null) {
            throw new ServiceException("该 Agent 未配置模型,无法执行 AI 调查");
        }
        AgentModel model = agentConfigCache.getModelById(agent.getModelId());
        if (model == null || StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getApiKey())
            || StringUtils.isBlank(model.getModelName())) {
            throw new ServiceException("模型配置不完整(缺少地址/密钥/模型标识)");
        }
        return model;
    }

    private void fail(CsInvestigation inv, String message) {
        if (inv == null) {
            return;
        }
        inv.setStatus("FAILED");
        inv.setErrorMessage(truncate(message, 500));
        inv.setUpdateTime(new Date());
        investigationMapper.updateById(inv);
    }

    private CsInvestigationVo toVo(CsInvestigation inv) {
        CsInvestigationVo vo = new CsInvestigationVo();
        vo.setId(inv.getId());
        vo.setTicketId(inv.getTicketId());
        vo.setConversationId(inv.getConversationId());
        vo.setDatasourceId(inv.getDatasourceId());
        vo.setAgentId(inv.getAgentId());
        vo.setTemplateCode(inv.getTemplateCode());
        vo.setStatus(inv.getStatus());
        vo.setReportMd(inv.getReportMd());
        vo.setConfidence(inv.getConfidence());
        vo.setErrorMessage(inv.getErrorMessage());
        vo.setConfirmedAt(inv.getConfirmedAt());
        vo.setCreateTime(inv.getCreateTime());
        if (StringUtils.isNotBlank(inv.getHypothesesJson())) {
            try {
                vo.setHypotheses(objectMapper.readValue(inv.getHypothesesJson(),
                    new TypeReference<List<Map<String, Object>>>() {
                    }));
            } catch (Exception ignore) {
                // 解析失败保留原文不展示,不阻断详情
            }
        }
        return vo;
    }

    private CsInvestigationVo.Evidence toEvidence(CsInvestigationEvidence e) {
        CsInvestigationVo.Evidence vo = new CsInvestigationVo.Evidence();
        vo.setSeq(e.getSeq());
        vo.setEvidenceType(e.getEvidenceType());
        vo.setHypothesisTitle(e.getHypothesisTitle());
        vo.setQueryText(e.getQueryText());
        vo.setResultDigest(e.getResultDigest());
        vo.setRowCount(e.getRowCount());
        vo.setElapsedMs(e.getElapsedMs());
        vo.setSuccess(e.getSuccess() != null && e.getSuccess() == 1);
        vo.setErrorMessage(e.getErrorMessage());
        return vo;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
