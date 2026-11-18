package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsGovernanceFacade;
import com.forgeagent.agent.service.governance.ReleaseGateService;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 客服治理契约实现(C4):挂靠 agent_info 的发布状态与版本号,不另建治理表。
 *
 * <p>批次 A(A3 门禁接线):基础检查沿用原口径(停用态/未绑定模型/提示词为空 快速失败),
 * 指标项委托 {@link ReleaseGateService#evaluate}(评测得分/近 7 天失败率/黄金基线退化,
 * 豁免与无样本语义由门禁内部处理)——无评测样本时仅执行基础检查,存量空间行为不变;
 * 只读评估不写 release_gate_execution,避免模板应用高频产生执行记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsGovernanceFacadeImpl implements CsGovernanceFacade {

    private final AgentInfoMapper agentInfoMapper;
    private final ReleaseGateService releaseGateService;

    @Override
    public boolean canPublish(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            if (agent == null) {
                return false;
            }
            // 0草稿/1发布/2停用;停用态禁止应用模板
            if ("2".equals(agent.getStatus())) {
                return false;
            }
            if (agent.getModelId() == null
                || agent.getSystemPrompt() == null || agent.getSystemPrompt().isBlank()) {
                return false;
            }
            ReleaseGateService.GateResult result = releaseGateService.evaluate(agent);
            if (!result.passed()) {
                log.warn("[CS] 发布门禁未通过 agentId={}: {}", agentId, result.reason());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[CS] 发布门禁检查失败 agentId={}: {}", agentId, e.getMessage());
            return false;
        }
    }

    @Override
    public Long currentPublishedVersion(Long agentId) {
        if (agentId == null) {
            return null;
        }
        try {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            return agent == null || agent.getPublishedVersion() == null
                ? null : agent.getPublishedVersion().longValue();
        } catch (Exception e) {
            return null;
        }
    }
}
