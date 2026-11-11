package com.forgeagent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agent.domain.AgentMessage;
import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.bo.AgentSessionBo;
import com.forgeagent.agentcore.domain.vo.AgentInfoVo;
import com.forgeagent.agent.domain.vo.AgentMessageVo;
import com.forgeagent.agent.domain.vo.AgentSessionVo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.agent.mapper.AgentMessageMapper;
import com.forgeagent.agent.mapper.AgentSessionMapper;
import com.forgeagent.agent.service.AgentChatAttachmentService;
import com.forgeagent.agent.service.IAgentSessionService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.MapstructUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 会话 Service 实现
 *
 * @author ForgeAgent
 */
@RequiredArgsConstructor
@Service
public class AgentSessionServiceImpl implements IAgentSessionService {

    /**
     * 消息列表默认分页条数
     */
    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 50;

    /**
     * 消息列表单页上限
     */
    private static final int MAX_MESSAGE_PAGE_SIZE = 200;

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentInfoMapper agentMapper;
    private final AgentChatAttachmentService attachmentService;

    @Override
    public PageResult<AgentSessionVo> queryPageList(AgentSessionBo bo, PageQuery pageQuery) {
        boolean ownOnly = !LoginHelper.isSuperAdmin();
        LambdaQueryWrapper<AgentSession> wrapper = new LambdaQueryWrapper<AgentSession>()
            .eq(ownOnly, AgentSession::getUserId, LoginHelper.getUserId())
            // UX-01: 调试会话不进正式会话列表
            .and(w -> w.isNull(AgentSession::getTemp).or().eq(AgentSession::getTemp, 0))
            .like(StringUtils.isNotBlank(bo.getKeyword()), AgentSession::getTitle, bo.getKeyword())
            .orderByDesc(AgentSession::getPinned)
            .orderByDesc(AgentSession::getLastMsgTime)
            .orderByDesc(AgentSession::getId);
        Page<AgentSessionVo> page = sessionMapper.selectVoPage(pageQuery.build(), wrapper);
        fillAgentNames(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentSessionVo insertByBo(AgentSessionBo bo) {
        AgentInfo agent = agentMapper.selectById(bo.getAgentId());
        if (agent == null) {
            throw new ServiceException("Agent 不存在");
        }
        if (!"1".equals(agent.getStatus())) {
            throw new ServiceException("Agent 未发布,无法创建会话");
        }
        AgentSession entity = new AgentSession();
        entity.setAgentId(bo.getAgentId());
        entity.setUserId(LoginHelper.getUserId());
        entity.setTitle(StringUtils.isBlank(bo.getTitle()) ? "新会话" : bo.getTitle());
        entity.setMessageCount(0);
        sessionMapper.insert(entity);
        AgentSessionVo vo = MapstructUtils.convert(entity, AgentSessionVo.class);
        fillAgentNames(List.of(vo));
        return vo;
    }

    @Override
    public Boolean updateByBo(AgentSessionBo bo) {
        AgentSession exists = getOwned(bo.getId());
        AgentSession update = new AgentSession();
        update.setId(exists.getId());
        update.setTitle(bo.getTitle());
        return sessionMapper.updateById(update) > 0;
    }

    @Override
    public Boolean updatePinned(Long id, String pinned) {
        AgentSession exists = getOwned(id);
        AgentSession update = new AgentSession();
        update.setId(exists.getId());
        update.setPinned("1".equals(pinned) ? "1" : "0");
        return sessionMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            AgentSession exists = getOwned(id);
            messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, exists.getId()));
            // 级联清理会话附件与文件临时索引
            attachmentService.cleanupBySession(exists.getId());
            sessionMapper.deleteById(exists.getId());
        }
        return true;
    }

    @Override
    public List<AgentMessageVo> listMessages(Long sessionId, Long beforeId, Integer limit) {
        getOwned(sessionId);
        int size = limit == null || limit <= 0 ? DEFAULT_MESSAGE_PAGE_SIZE : Math.min(limit, MAX_MESSAGE_PAGE_SIZE);
        // 游标分页:按 id 倒序取一页后再反转,保证返回按时间正序;beforeId 用于向前加载更早消息
        List<AgentMessageVo> list = messageMapper.selectVoList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getSessionId, sessionId)
            .lt(beforeId != null, AgentMessage::getId, beforeId)
            .orderByDesc(AgentMessage::getId)
            .last("limit " + size));
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * 校验会话存在且属于当前用户(超管放行)
     */
    @Override
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

    private void fillAgentNames(List<AgentSessionVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> agentIds = list.stream().map(AgentSessionVo::getAgentId).filter(Objects::nonNull).distinct().toList();
        if (agentIds.isEmpty()) {
            return;
        }
        Map<Long, String> names = agentMapper.selectByIds(agentIds).stream()
            .collect(Collectors.toMap(AgentInfo::getId, AgentInfo::getName, (a, b) -> a));
        list.forEach(vo -> vo.setAgentName(names.get(vo.getAgentId())));
    }
}
