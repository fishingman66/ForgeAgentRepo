package com.forgeagent.customerservice.service;

import com.forgeagent.agent.api.customerservice.CsGovernanceFacade;
import com.forgeagent.customerservice.compliance.CsComplianceService;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.customerservice.mapper.CsChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服 Agent 模板(C4):挂靠 forge-agent 的版本/门禁体系,不另建模板表。
 *
 * <p>模板只覆盖渠道级配置(兜底策略、话术、语种、知识库),不直接改 agent_info,
 * 避免绕过 B1 的「已发布 Agent 禁止直接改配置」约束。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsAgentTemplateService {

    private final CsChannelMapper channelMapper;
    private final CsGovernanceFacade governanceFacade;
    private final CsComplianceService complianceService;

    public List<com.forgeagent.customerservice.domain.vo.CsTemplateItemVo> templates() {
        return List.of(
            template("standard", "标准客服", "zero_hit", "抱歉，暂时没有找到相关答案，已为你转接人工客服。",
                "Sorry, I could not find a relevant answer. Connecting you to an agent.", "zh-CN"),
            template("ecommerce", "电商售后", "keyword", "关于订单、退款与物流的问题，正在为你核实，也可以转人工处理。",
                "For orders, refunds or shipping, please hold on or request a human agent.", "zh-CN"),
            template("saas", "技术支持", "zero_hit", "这个问题需要进一步排查，已记录并为你转接技术支持。",
                "This needs further investigation. We have logged it and will connect you to support.", "zh-CN")
        );
    }

    /** 应用模板到渠道;发布门禁不通过时拒绝,并留审计。 */
    public CsChannel apply(String templateCode, String channelCode) {
        com.forgeagent.customerservice.domain.vo.CsTemplateItemVo chosen = templates().stream()
            .filter(t -> templateCode.equals(t.getCode())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("未知客服模板: " + templateCode));
        CsChannel channel = channelMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CsChannel>()
                .eq(CsChannel::getChannelCode, channelCode).last("limit 1"));
        if (channel == null) {
            throw new IllegalArgumentException("渠道不存在: " + channelCode);
        }
        if (!governanceFacade.canPublish(channel.getAgentId())) {
            throw new IllegalStateException("客服 Agent 未通过发布门禁,模板不可应用");
        }
        channel.setHandoffPolicy(chosen.getHandoffPolicy());
        channel.setFallbackReply(chosen.getFallbackReply());
        channel.setFallbackReplyEn(chosen.getFallbackReplyEn());
        channel.setLocale(chosen.getLocale());
        channelMapper.updateById(channel);
        complianceService.record("TEMPLATE_APPLY", "SYSTEM", templateCode,
            channel.getWorkspaceId(), channelCode, "template=" + templateCode);
        return channel;
    }

    private static com.forgeagent.customerservice.domain.vo.CsTemplateItemVo template(String code, String name, String handoffPolicy,
                                                String zh, String en, String locale) {
        com.forgeagent.customerservice.domain.vo.CsTemplateItemVo item = new com.forgeagent.customerservice.domain.vo.CsTemplateItemVo();
        item.setCode(code);
        item.setName(name);
        item.setHandoffPolicy(handoffPolicy);
        item.setFallbackReply(zh);
        item.setFallbackReplyEn(en);
        item.setLocale(locale);
        return item;
    }
}
