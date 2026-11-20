package com.forgeagent.agent.api.customerservice.impl;

import com.forgeagent.agent.api.customerservice.CsChatFacade;
import com.forgeagent.agent.domain.bo.ChatSendBo;
import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.IAgentChatService;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.agent.service.run.RunCancelRegistry;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.UtilityAgents;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Duration;
import com.forgeagent.agentcore.mapper.AgentInfoMapper;
import com.forgeagent.common.core.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 客服对话契约实现(S0):复用 AgentChatServiceImpl 的完整编排链。
 *
 * <p>访客归属通过 ChatSendBo 的两个 caller 字段显式传递,控制台路径(caller 字段为空)
 * 行为完全不变;因此这里不做任何登录态模拟,也不复制对话逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsChatFacadeImpl implements CsChatFacade {

    private final IAgentChatService chatService;
    private final RunCancelRegistry runCancel;
    private final AgentInfoMapper agentInfoMapper;
    private final AgentConfigCacheService agentConfigCache;

    @Override
    public ChatSendVo send(CsChatRequest request) {
        return chatService.send(toBo(request));
    }

    @Override
    public void stream(CsChatRequest request, StreamEmitter emitter) {
        chatService.streamSend(toBo(request), emitter);
    }

    @Override
    public void interrupt(Long sessionId) {
        if (sessionId != null) {
            runCancel.requestCancel(sessionId);
        }
    }

    @Override
    public boolean hasExecutableTools(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            return agent != null
                && (StringUtils.isNotBlank(agent.getToolIdsStr())
                || StringUtils.isNotBlank(agent.getMcpIdsStr()));
        } catch (Exception e) {
            log.warn("[CS] 查询 Agent 工具配置失败 agentId={}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /** A3:短文本改写,模型不可用/超时/异常一律返回 null,由坐席端保留原文。 */
    @Override
    public String rewrite(Long agentId, String text, String mode) {
        if (agentId == null || text == null || text.isBlank() || text.length() > 2000) {
            return null;
        }
        String sys = switch (mode == null ? "polish" : mode) {
            case "translate_en" -> "你是客服话术翻译助手。把用户消息翻译成自然、礼貌的英文。只输出译文,不要解释、不要引号。";
            case "translate_zh" -> "你是客服话术翻译助手。把用户消息翻译成自然、礼貌的简体中文。只输出译文,不要解释、不要引号。";
            case "polish" -> "你是客服话术润色助手。改写用户消息使其更清晰、礼貌、专业,保持原意与原语言,不新增事实。只输出改写结果,不要解释。";
            default -> null;
        };
        if (sys == null) {
            return null;
        }
        try {
            AgentInfo agent = agentConfigCache.getAgentById(agentId);
            if (agent == null || agent.getModelId() == null) {
                agent = agentInfoMapper.selectById(agentId);
            }
            if (agent == null || agent.getModelId() == null) {
                return null;
            }
            AgentModel model = agentConfigCache.getModelById(agent.getModelId());
            if (model == null || StringUtils.isBlank(model.getBaseUrl())
                || StringUtils.isBlank(model.getApiKey()) || StringUtils.isBlank(model.getModelName())) {
                return null;
            }
            Msg result = UtilityAgents.build("cs-rewrite", sys, model)
                .call(java.util.List.of(Msg.builder().name("user").role(MsgRole.USER).textContent(text).build()))
                .block(Duration.ofSeconds(20));
            if (result == null || result.getTextContent() == null) {
                return null;
            }
            String out = result.getTextContent().trim();
            return out.isEmpty() || out.length() > 2000 ? null : out;
        } catch (Exception e) {
            log.warn("[CS] A3 文本改写失败 agentId={} mode={}: {}", agentId, mode, e.getMessage());
            return null;
        }
    }

    /** B3:FAQ 候选答案生成,严格只输出 JSON,失败返回 null 由客服侧 fail-safe。 */
    @Override
    public String draftAnswer(Long agentId, String question, String reference) {
        if (agentId == null || question == null || question.isBlank() || question.length() > 500) {
            return null;
        }
        String sys = "你是客服知识库编辑。基于【问题】和【参考资料】生成一条准确、简洁的候选答案。"
            + "只输出一个 JSON 对象,格式为 {\"answer\":\"...\",\"citations\":[\"...\"],\"confidence\":0.0}。"
            + "citations 是参考资料里支撑答案的原文片段(最多3条);confidence 是 0~1 的把握度。"
            + "参考资料不足以可靠回答时 confidence 必须低于 0.6。不要编造参考资料里没有的事实。不要输出 JSON 以外的文字。";
        try {
            AgentInfo agent = agentConfigCache.getAgentById(agentId);
            if (agent == null || agent.getModelId() == null) {
                agent = agentInfoMapper.selectById(agentId);
            }
            if (agent == null || agent.getModelId() == null) {
                return null;
            }
            AgentModel model = agentConfigCache.getModelById(agent.getModelId());
            if (model == null || StringUtils.isBlank(model.getBaseUrl())
                || StringUtils.isBlank(model.getApiKey()) || StringUtils.isBlank(model.getModelName())) {
                return null;
            }
            String prompt = "问题:" + question + "\n"
                + "参考资料:" + (reference == null || reference.isBlank() ? "无" : reference.substring(0, Math.min(4000, reference.length())))
                + "\n" + "请只输出 JSON:";
            Msg result = UtilityAgents.build("cs-faq-draft", sys, model)
                .call(java.util.List.of(Msg.builder().name("user").role(MsgRole.USER).textContent(prompt).build()))
                .block(Duration.ofSeconds(40));
            if (result == null || result.getTextContent() == null) {
                return null;
            }
            String raw = result.getTextContent();
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            String json = raw.substring(start, end + 1);
            if (json.length() > 8000) {
                return null;
            }
            return json;
        } catch (Exception e) {
            log.warn("[CS] B3 FAQ 候选生成失败 agentId={}: {}", agentId, e.getMessage());
            return null;
        }
    }

    @Override
    public Integer decidePinnedVersion(Long agentId, Long visitorId) {
        if (agentId == null || visitorId == null) {
            return null;
        }
        try {
            AgentInfo agent = agentInfoMapper.selectById(agentId);
            if (agent == null || agent.getPublishedVersion() == null) {
                // 未发布过:没有快照可锁,走现行版
                return null;
            }
            int percent = agent.getRolloutPercent() == null ? 100 : agent.getRolloutPercent();
            if (percent <= 0) {
                return null;
            }
            if (percent >= 100) {
                return agent.getPublishedVersion();
            }
            int bucket = Math.floorMod(visitorId.hashCode(), 100);
            return bucket < percent ? agent.getPublishedVersion() : null;
        } catch (Exception e) {
            log.warn("[CS] D1 灰度判定失败 agentId={} visitorId={}: {}", agentId, visitorId, e.getMessage());
            return null;
        }
    }

    private ChatSendBo toBo(CsChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("客服对话请求不能为空");
        }
        if (request.callerUserId() == null) {
            throw new IllegalArgumentException("客服对话必须指定渠道服务账号 callerUserId");
        }
        ChatSendBo bo = new ChatSendBo();
        bo.setSessionId(request.sessionId());
        bo.setAgentId(request.agentId());
        bo.setContent(request.content());
        bo.setCallerUserId(request.callerUserId());
        bo.setCallerWorkspaceId(request.callerWorkspaceId());
        bo.setPrefetched(request.prefetched());
        bo.setCsGuardContext(request.guardContext());
        return bo;
    }
}
