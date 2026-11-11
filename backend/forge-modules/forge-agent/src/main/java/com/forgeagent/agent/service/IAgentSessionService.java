package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.AgentSession;
import com.forgeagent.agent.domain.bo.AgentSessionBo;
import com.forgeagent.agent.domain.vo.AgentMessageVo;
import com.forgeagent.agent.domain.vo.AgentSessionVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * Agent 会话 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentSessionService {

    PageResult<AgentSessionVo> queryPageList(AgentSessionBo bo, PageQuery pageQuery);

    AgentSessionVo insertByBo(AgentSessionBo bo);

    Boolean updateByBo(AgentSessionBo bo);

    /**
     * 置顶/取消置顶会话
     *
     * @param pinned "1" 置顶,其他取消置顶
     */
    Boolean updatePinned(Long id, String pinned);

    Boolean deleteByIds(List<Long> ids);

    /**
     * 会话消息列表(按时间正序),游标分页
     *
     * @param beforeId 只返回 id 小于该值的消息(为空表示取最新一页)
     * @param limit    本页条数(为空默认 50,上限 200)
     */
    List<AgentMessageVo> listMessages(Long sessionId, Long beforeId, Integer limit);

    /**
     * 校验会话存在且属于当前用户(超管放行),否则抛出 ServiceException
     */
    AgentSession getOwned(Long sessionId);
}
