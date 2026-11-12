package com.forgeagent.rag.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbVlmConfig;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.mapper.AgentKbVlmConfigMapper;
import org.springframework.stereotype.Component;

/**
 * 图片识别模型解析:决定"这次描述用哪份配置"。
 *
 * <p>优先级:<b>工作空间行</b> > <b>平台默认行(workspace_id=0)</b> > <b>全局 rag.knowledge.vlm-model</b>
 * (后者是 R5-5 首版入口,保留兼容)。PLATFORM 模式复用 agent_model 的凭证,
 * CUSTOM 模式用本表存的 baseUrl + 解密后的 apiKey。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class KbVlmConfigResolver {

    public static final String SOURCE_PLATFORM = "PLATFORM";
    public static final String SOURCE_CUSTOM = "CUSTOM";

    private final AgentKbVlmConfigMapper configMapper;
    private final AgentModelMapper modelMapper;
    private final KnowledgeProperties properties;
    private final KbSecretCipher cipher;

    /**
     * 解析结果
     *
     * @param from 命中来源:WORKSPACE / PLATFORM_DEFAULT / GLOBAL
     */
    public record Resolved(String baseUrl, String apiKey, String modelName, String from) {
    }

    public Resolved resolve(AgentKnowledge kb) {
        Long workspaceId = kb == null ? null : kb.getWorkspaceId();
        if (workspaceId != null) {
            Resolved r = fromRow(enabledRow(workspaceId), "WORKSPACE");
            if (r != null) {
                return r;
            }
        }
        Resolved platformDefault = fromRow(enabledRow(0L), "PLATFORM_DEFAULT");
        if (platformDefault != null) {
            return platformDefault;
        }
        // 兼容 R5-5 首版:全局 vlm-model 指向 agent_model
        if (StringUtils.isNotBlank(properties.getVlmModel())) {
            AgentModel model = modelByName(properties.getVlmModel());
            if (model != null) {
                return new Resolved(model.getBaseUrl(), model.getApiKey(), model.getModelName(), "GLOBAL");
            }
        }
        return null;
    }

    /**
     * 用入参组装(测试连通用):CUSTOM 的 apiKey 为空时回退已存密文
     */
    public Resolved fromBo(com.forgeagent.rag.domain.bo.KbVlmConfigBo bo) {
        String type = StringUtils.isBlank(bo.getSourceType()) ? SOURCE_PLATFORM : bo.getSourceType().toUpperCase();
        if (SOURCE_CUSTOM.equals(type)) {
            String apiKey = StringUtils.isBlank(bo.getApiKey()) ? storedPlainKey(bo.getWorkspaceId()) : bo.getApiKey();
            return StringUtils.isBlank(bo.getBaseUrl()) || StringUtils.isBlank(bo.getModelName())
                ? null
                : new Resolved(bo.getBaseUrl().trim(), apiKey, bo.getModelName().trim(), "INPUT");
        }
        AgentModel model = modelByName(bo.getModelName());
        return model == null ? null
            : new Resolved(model.getBaseUrl(), model.getApiKey(), model.getModelName(), "INPUT");
    }

    /**
     * 某空间已存 API Key 明文(仅在需要调用时解密,不对外返回)
     */
    public String storedPlainKey(Long workspaceId) {
        AgentKbVlmConfig row = enabledRow(workspaceId == null ? 0L : workspaceId);
        return row == null ? null : cipher.decrypt(row.getApiKeyCipher());
    }

    private Resolved fromRow(AgentKbVlmConfig row, String from) {
        if (row == null) {
            return null;
        }
        String type = StringUtils.isBlank(row.getSourceType()) ? SOURCE_PLATFORM : row.getSourceType().toUpperCase();
        if (SOURCE_CUSTOM.equals(type)) {
            if (StringUtils.isBlank(row.getBaseUrl()) || StringUtils.isBlank(row.getModelName())) {
                return null;
            }
            return new Resolved(row.getBaseUrl().trim(), cipher.decrypt(row.getApiKeyCipher()),
                row.getModelName().trim(), from);
        }
        AgentModel model = modelByName(row.getModelName());
        return model == null ? null
            : new Resolved(model.getBaseUrl(), model.getApiKey(), model.getModelName(), from);
    }

    private AgentKbVlmConfig enabledRow(Long workspaceId) {
        AgentKbVlmConfig row = configMapper.selectOne(new LambdaQueryWrapper<AgentKbVlmConfig>()
            .eq(AgentKbVlmConfig::getWorkspaceId, workspaceId)
            .last("limit 1"));
        return row != null && Integer.valueOf(1).equals(row.getEnabled()) ? row : null;
    }

    private AgentModel modelByName(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return null;
        }
        var models = modelMapper.selectList(new LambdaQueryWrapper<AgentModel>()
            .eq(AgentModel::getModelName, modelName.trim())
            .last("limit 1"));
        if (models.isEmpty()) {
            return null;
        }
        AgentModel model = models.getFirst();
        return StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getApiKey())
            ? null : model;
    }
}
