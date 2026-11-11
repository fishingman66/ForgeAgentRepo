package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agentcore.domain.AgentWorkspaceMember;
import com.forgeagent.agent.domain.bo.AgentFeedbackBo;
import com.forgeagent.agent.domain.vo.AgentFeedbackVo;
import com.forgeagent.agentcore.enums.WorkspaceRole;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.mapper.AgentTokenUsageMapper;
import com.forgeagent.agentcore.mapper.AgentWorkspaceMemberMapper;
import com.forgeagent.agent.service.IAgentFeedbackService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.excel.utils.ExcelBuilder;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 消息反馈管理 Service 实现(UX-02)
 *
 * <p>可见范围:超管全量;空间管理员仅其管理空间的会话消息;其他角色不允许(由权限点拦截)。</p>
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentFeedbackServiceImpl implements IAgentFeedbackService {

    private final AgentMessageMapper messageMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentInfoMapper agentMapper;
    private final AgentWorkspaceMemberMapper memberMapper;
    private final AgentTokenUsageMapper usageMapper;

    @Override
    public PageResult<AgentFeedbackVo> queryPageList(AgentFeedbackBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AgentMessage> wrapper = buildWrapper(bo);
        Page<AgentMessage> page = messageMapper.selectPage(pageQuery.build(), wrapper);
        List<AgentFeedbackVo> rows = page.getRecords().stream().map(this::toVo).toList();
        fillNames(rows);
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public void export(AgentFeedbackBo bo, HttpServletResponse response) {
        List<AgentMessage> list = messageMapper.selectList(buildWrapper(bo).last("limit 10000"));
        List<AgentFeedbackVo> rows = list.stream().map(this::toVo).toList();
        fillNames(rows);
        ExcelBuilder.of(rows, AgentFeedbackVo.class).sheetName("消息反馈").toResponse(response);
    }

    /**
     * 反馈消息过滤:仅助手消息且有反馈值;按 agent(经会话)/类型/反馈时间筛选
     */
    private LambdaQueryWrapper<AgentMessage> buildWrapper(AgentFeedbackBo bo) {
        LambdaQueryWrapper<AgentMessage> w = new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getRole, "assistant")
            .ne(AgentMessage::getFeedback, "")
            .isNotNull(AgentMessage::getFeedback)
            .eq(StringUtils.isNotBlank(bo.getFeedback()), AgentMessage::getFeedback, bo.getFeedback())
            .ge(StringUtils.isNotBlank(bo.getBeginTime()), AgentMessage::getFeedbackTime, bo.getBeginTime())
            .le(StringUtils.isNotBlank(bo.getEndTime()), AgentMessage::getFeedbackTime, bo.getEndTime())
            .orderByDesc(AgentMessage::getFeedbackTime);
        // agent 筛选:先定位该 agent 的会话集合
        if (bo.getAgentId() != null) {
            List<Long> sessionIds = sessionMapper.selectList(new LambdaQueryWrapper<AgentSession>()
                    .eq(AgentSession::getAgentId, bo.getAgentId()))
                .stream().map(AgentSession::getId).toList();
            if (sessionIds.isEmpty()) {
                w.eq(AgentMessage::getSessionId, -1L);
            } else {
                w.in(AgentMessage::getSessionId, sessionIds);
            }
        }
        // 空间管理员数据范围:仅其管理空间的会话
        if (!LoginHelper.isSuperAdmin()) {
            List<Long> adminWs = memberMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceMember>()
                    .eq(AgentWorkspaceMember::getUserId, LoginHelper.getUserId())
                    .eq(AgentWorkspaceMember::getRole, WorkspaceRole.ADMIN.getCode()))
                .stream().map(AgentWorkspaceMember::getWorkspaceId).toList();
            if (adminWs.isEmpty()) {
                w.eq(AgentMessage::getSessionId, -1L);
            } else {
                List<Long> agentIds = agentMapper.selectList(new LambdaQueryWrapper<AgentInfo>()
                        .in(AgentInfo::getWorkspaceId, adminWs))
                    .stream().map(AgentInfo::getId).toList();
                if (agentIds.isEmpty()) {
                    w.eq(AgentMessage::getSessionId, -1L);
                } else {
                    List<Long> sessionIds = sessionMapper.selectList(new LambdaQueryWrapper<AgentSession>()
                            .in(AgentSession::getAgentId, agentIds))
                        .stream().map(AgentSession::getId).toList();
                    if (sessionIds.isEmpty()) {
                        w.eq(AgentMessage::getSessionId, -1L);
                    } else {
                        w.in(AgentMessage::getSessionId, sessionIds);
                    }
                }
            }
        }
        return w;
    }

    private AgentFeedbackVo toVo(AgentMessage m) {
        AgentFeedbackVo v = new AgentFeedbackVo();
        v.setMessageId(m.getId());
        v.setSessionId(m.getSessionId());
        v.setFeedback(m.getFeedback());
        v.setFeedbackTags(m.getFeedbackTags());
        v.setFeedbackComment(m.getFeedbackComment());
        v.setFeedbackTime(m.getFeedbackTime());
        v.setMsgTime(m.getCreateTime());
        String content = m.getContent() == null ? "" : m.getContent();
        v.setMsgSnapshot(content.length() > 200 ? content.substring(0, 200) + "..." : content);
        return v;
    }

    /**
     * 批量填充 Agent 名称与反馈人昵称(避免循环中逐条查询)
     */
    private void fillNames(List<AgentFeedbackVo> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> sessionIds = rows.stream().map(AgentFeedbackVo::getSessionId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, AgentSession> sessionMap = sessionIds.isEmpty() ? Map.of()
            : sessionMapper.selectByIds(sessionIds).stream()
                .collect(Collectors.toMap(AgentSession::getId, s -> s, (a, b) -> a));
        List<Long> agentIds = sessionMap.values().stream().map(AgentSession::getAgentId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> agentNameMap = agentIds.isEmpty() ? Map.of()
            : agentMapper.selectByIds(agentIds).stream().collect(Collectors.toMap(
                AgentInfo::getId, a -> StringUtils.defaultIfBlank(a.getName(), ""), (a, b) -> a));
        for (AgentFeedbackVo v : rows) {
            AgentSession s = sessionMap.get(v.getSessionId());
            if (s != null) {
                v.setAgentId(s.getAgentId());
                v.setUserId(s.getUserId());
                String name = agentNameMap.get(s.getAgentId());
                v.setAgentName(StringUtils.isBlank(name) ? ("Agent#" + s.getAgentId()) : name);
            }
        }
        List<Long> userIds = rows.stream().map(AgentFeedbackVo::getUserId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nickMap = userIds.isEmpty() ? Map.of()
            : usageMapper.selectNickNamesByIds(userIds).stream().collect(Collectors.toMap(
                row -> ((Number) row.get("userId")).longValue(),
                row -> row.get("nickName") == null ? "" : row.get("nickName").toString(),
                (a, b) -> a));
        for (AgentFeedbackVo v : rows) {
            if (v.getUserId() != null) {
                String nick = nickMap.get(v.getUserId());
                v.setUserName(StringUtils.isBlank(nick) ? String.valueOf(v.getUserId()) : nick);
            }
        }
    }
}
