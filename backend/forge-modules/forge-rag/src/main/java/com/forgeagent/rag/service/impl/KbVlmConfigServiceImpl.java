package com.forgeagent.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.service.AgentScopeModelFactory;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.domain.AgentKbVlmConfig;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.domain.bo.KbVlmConfigBo;
import com.forgeagent.rag.domain.vo.KbVlmConfigVo;
import com.forgeagent.rag.mapper.AgentKbVlmConfigMapper;
import com.forgeagent.rag.service.KbVlmConfigService;
import com.forgeagent.rag.service.support.KbSecretCipher;
import com.forgeagent.rag.service.support.KbVlmConfigResolver;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片识别模型配置服务实现
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KbVlmConfigServiceImpl implements KbVlmConfigService {

    private final AgentKbVlmConfigMapper configMapper;
    private final AgentModelMapper modelMapper;
    private final KbVlmConfigResolver resolver;
    private final KbSecretCipher cipher;

    @Override
    public KbVlmConfigVo get(Long workspaceId) {
        long ws = workspaceId == null ? 0L : workspaceId;
        AgentKbVlmConfig row = configMapper.selectOne(new LambdaQueryWrapper<AgentKbVlmConfig>()
            .eq(AgentKbVlmConfig::getWorkspaceId, ws)
            .last("limit 1"));
        KbVlmConfigVo vo = new KbVlmConfigVo();
        vo.setWorkspaceId(ws);
        if (row != null) {
            vo.setSourceType(row.getSourceType());
            vo.setModelName(row.getModelName());
            vo.setBaseUrl(row.getBaseUrl());
            String plain = cipher.decrypt(row.getApiKeyCipher());
            vo.setHasApiKey(StringUtils.isNotBlank(plain));
            vo.setApiKeyMasked(KbSecretCipher.mask(plain));
            vo.setEnabled(row.getEnabled());
            vo.setUpdateTime(row.getUpdateTime());
        } else {
            vo.setSourceType(KbVlmConfigResolver.SOURCE_PLATFORM);
            vo.setEnabled(0);
            vo.setHasApiKey(false);
        }
        // 实际生效来源:便于界面提示"这份配置到底有没有被用上"
        AgentKnowledge probe = new AgentKnowledge();
        probe.setWorkspaceId(ws == 0L ? null : ws);
        KbVlmConfigResolver.Resolved resolved = resolver.resolve(probe);
        vo.setEffectiveSource(resolved == null ? "NONE" : resolved.from());
        return vo;
    }

    @Override
    public void save(KbVlmConfigBo bo) {
        if (bo == null || bo.getWorkspaceId() == null) {
            throw new ServiceException("工作空间不能为空");
        }
        String type = StringUtils.isBlank(bo.getSourceType())
            ? KbVlmConfigResolver.SOURCE_PLATFORM : bo.getSourceType().toUpperCase();
        if (!List.of(KbVlmConfigResolver.SOURCE_PLATFORM, KbVlmConfigResolver.SOURCE_CUSTOM).contains(type)) {
            throw new ServiceException("配置来源只支持 PLATFORM / CUSTOM");
        }
        long ws = bo.getWorkspaceId();
        AgentKbVlmConfig existing = configMapper.selectOne(new LambdaQueryWrapper<AgentKbVlmConfig>()
            .eq(AgentKbVlmConfig::getWorkspaceId, ws)
            .last("limit 1"));

        String cipherText = existing == null ? null : existing.getApiKeyCipher();
        String baseUrl = null;
        if (KbVlmConfigResolver.SOURCE_PLATFORM.equals(type)) {
            if (StringUtils.isBlank(bo.getModelName())) {
                throw new ServiceException("请选择平台模型");
            }
            AgentModel model = modelMapper.selectList(new LambdaQueryWrapper<AgentModel>()
                    .eq(AgentModel::getModelName, bo.getModelName().trim()).last("limit 1"))
                .stream().findFirst().orElse(null);
            if (model == null) {
                throw new ServiceException("平台模型不存在:" + bo.getModelName());
            }
            if (StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getApiKey())) {
                throw new ServiceException("该平台模型缺少接入信息(baseUrl/apiKey),请先完善模型配置");
            }
        } else {
            if (StringUtils.isBlank(bo.getBaseUrl()) || StringUtils.isBlank(bo.getModelName())) {
                throw new ServiceException("自定义模式必须填写 baseUrl 与 modelName");
            }
            baseUrl = bo.getBaseUrl().trim();
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new ServiceException("baseUrl 必须以 http:// 或 https:// 开头");
            }
            if (StringUtils.isNotBlank(bo.getApiKey())) {
                cipherText = cipher.encrypt(bo.getApiKey().trim());
            }
            if (StringUtils.isBlank(cipherText)) {
                throw new ServiceException("首次配置自定义模型必须填写 API Key");
            }
        }
        Integer enabled = bo.getEnabled() == null ? 1 : bo.getEnabled();
        if (existing == null) {
            AgentKbVlmConfig row = new AgentKbVlmConfig();
            row.setWorkspaceId(ws);
            row.setSourceType(type);
            row.setModelName(bo.getModelName().trim());
            row.setBaseUrl(baseUrl);
            row.setApiKeyCipher(KbVlmConfigResolver.SOURCE_CUSTOM.equals(type) ? cipherText : null);
            row.setEnabled(enabled);
            row.setUpdateBy(com.forgeagent.common.satoken.utils.LoginHelper.getUserId());
            row.setCreateTime(new Date());
            configMapper.insert(row);
        } else {
            configMapper.update(null, new LambdaUpdateWrapper<AgentKbVlmConfig>()
                .eq(AgentKbVlmConfig::getId, existing.getId())
                .set(AgentKbVlmConfig::getSourceType, type)
                .set(AgentKbVlmConfig::getModelName, bo.getModelName().trim())
                .set(AgentKbVlmConfig::getBaseUrl, baseUrl)
                .set(AgentKbVlmConfig::getApiKeyCipher,
                    KbVlmConfigResolver.SOURCE_CUSTOM.equals(type) ? cipherText : null)
                .set(AgentKbVlmConfig::getEnabled, enabled)
                .set(AgentKbVlmConfig::getUpdateBy,
                    com.forgeagent.common.satoken.utils.LoginHelper.getUserId())
                .set(AgentKbVlmConfig::getUpdateTime, new Date()));
        }
    }

    @Override
    public Map<String, Object> test(KbVlmConfigBo bo) {
        Map<String, Object> result = new LinkedHashMap<>();
        KbVlmConfigResolver.Resolved resolved = resolver.fromBo(bo);
        if (resolved == null) {
            result.put("ok", false);
            result.put("message", "配置不完整:平台模型不存在,或自定义模式缺少 baseUrl/modelName/API Key");
            return result;
        }
        try {
            Model model = AgentScopeModelFactory.build(resolved.baseUrl(), resolved.apiKey(),
                resolved.modelName(), 0.0, 16);
            ReActAgent agent = ReActAgent.builder().name("vlm-test").model(model).maxIters(1).build();
            Msg reply = agent.call(Msg.builder().name("user").role(MsgRole.USER)
                    .textContent("回复 OK 两个字母即可").build())
                .block(Duration.ofSeconds(20));
            String text = reply == null ? null : reply.getTextContent();
            result.put("ok", StringUtils.isNotBlank(text));
            result.put("message", StringUtils.isNotBlank(text) ? "连通正常:" + StringUtils.substring(text, 0, 40)
                : "模型返回为空");
            result.put("model", resolved.modelName());
        } catch (Exception e) {
            log.warn("[知识库] 图片识别模型连通性测试失败: {}", e.getMessage());
            result.put("ok", false);
            result.put("message", "连通失败:" + StringUtils.substring(e.getMessage(), 0, 200));
            result.put("model", resolved.modelName());
        }
        return result;
    }
}
