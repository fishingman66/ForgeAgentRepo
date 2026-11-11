package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentWorkflowEndpointBo;
import com.forgeagent.agent.domain.vo.AgentWorkflowEndpointVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.Map;

/**
 * 工作流对外 API 端点 Service(G2)
 *
 * @author ForgeAgent
 */
public interface IAgentWorkflowEndpointService {

    PageResult<AgentWorkflowEndpointVo> queryPageList(Long agentId, PageQuery pageQuery);

    Long insertByBo(AgentWorkflowEndpointBo bo);

    Boolean updateByBo(AgentWorkflowEndpointBo bo);

    Boolean changeStatus(Long id, String status);

    Boolean deleteByIds(java.util.List<Long> ids);

    /**
     * 外部调用入口:IP 预限流 → 鉴权(失败计数) → 端点限流 → 同步执行工作流 → 返回终态结果
     *
     * @param endpointCode     端点编码
     * @param authorizationHeader Authorization 头(期望 "Bearer ak-...")
     * @param body             请求体(透传为 start.* 变量的 JSON 对象)
     * @param idempotencyHeader Idempotency-Key 请求头(可选)
     * @param clientIp         调用方 IP(限流与失败计数,取 X-Forwarded-For 首段或 remoteAddr)
     * @return runId / status / output / message / costMs
     */
    Map<String, Object> invoke(String endpointCode, String authorizationHeader, String body,
                               String idempotencyHeader, String clientIp);
}
