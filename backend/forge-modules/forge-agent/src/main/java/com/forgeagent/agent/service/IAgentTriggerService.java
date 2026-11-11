package com.forgeagent.agent.service;

import com.forgeagent.agent.domain.bo.AgentTriggerBo;
import com.forgeagent.agent.domain.vo.AgentTriggerLogVo;
import com.forgeagent.agent.domain.vo.AgentTriggerVo;
import com.forgeagent.agent.domain.vo.TriggerRunVo;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * Agent 触发器 Service 接口
 *
 * @author ForgeAgent
 */
public interface IAgentTriggerService {

    /**
     * 分页查询触发器
     */
    PageResult<AgentTriggerVo> queryPageList(AgentTriggerBo bo, PageQuery pageQuery);

    /**
     * 查询触发器详情
     */
    AgentTriggerVo queryById(Long id);

    /**
     * 新增触发器
     */
    Boolean insertByBo(AgentTriggerBo bo);

    /**
     * 修改触发器
     */
    Boolean updateByBo(AgentTriggerBo bo);

    /**
     * 启用/停用触发器(0启用 1停用)
     */
    Boolean changeEnabled(Long id, String enabled);

    /**
     * 删除触发器(含执行记录)
     */
    Boolean deleteByIds(List<Long> ids);

    /**
     * 分页查询触发器执行记录
     */
    PageResult<AgentTriggerLogVo> queryLogPageList(Long triggerId, PageQuery pageQuery);

    /**
     * 手动立即执行一次
     */
    TriggerRunVo runNow(Long id);

    /**
     * Webhook 事件触发(以密钥鉴权,免登录)
     *
     * @param secret           Webhook 密钥
     * @param body             外部事件负载(可选,JSON 或纯文本)
     * @param idempotencyHeader G3:请求头 Idempotency-Key(非空按其防重;缺失不做幂等)
     */
    TriggerRunVo runWebhook(String secret, String body, String idempotencyHeader);

    /**
     * Cron 定时触发执行(调度器回调入口)
     */
    TriggerRunVo executeCron(Long id);

    /**
     * 查询全部启用中的 Cron 触发器(供调度器注册)
     */
    List<AgentTriggerVo> listEnabledCronTriggers();

}
