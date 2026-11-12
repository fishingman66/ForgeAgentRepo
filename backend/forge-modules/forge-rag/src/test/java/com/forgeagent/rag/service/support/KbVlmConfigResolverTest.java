package com.forgeagent.rag.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.rag.config.KnowledgeProperties;
import com.forgeagent.rag.domain.AgentKbVlmConfig;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.rag.mapper.AgentKbVlmConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图片识别模型解析单测:工作空间 > 平台默认 > 全局,PLATFORM/CUSTOM 两类来源
 *
 * @author ForgeAgent
 */
@Tag("dev")
class KbVlmConfigResolverTest {

    @BeforeAll
    static void warmUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AgentKbVlmConfig.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AgentModel.class);
    }

    private final AgentKbVlmConfigMapper configMapper = mock(AgentKbVlmConfigMapper.class);
    private final AgentModelMapper modelMapper = mock(AgentModelMapper.class);
    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final KbSecretCipher cipher = new KbSecretCipher(properties);
    private final KbVlmConfigResolver resolver =
        new KbVlmConfigResolver(configMapper, modelMapper, properties, cipher);

    private AgentKnowledge kb(Long workspaceId) {
        AgentKnowledge kb = new AgentKnowledge();
        kb.setId(1L);
        kb.setWorkspaceId(workspaceId);
        return kb;
    }

    private AgentKbVlmConfig custom(long ws, String baseUrl, String key) {
        AgentKbVlmConfig row = new AgentKbVlmConfig();
        row.setWorkspaceId(ws);
        row.setSourceType("CUSTOM");
        row.setModelName("my-vl");
        row.setBaseUrl(baseUrl);
        row.setApiKeyCipher(cipher.encrypt(key));
        row.setEnabled(1);
        return row;
    }

    private AgentKbVlmConfig platform(long ws, String modelName) {
        AgentKbVlmConfig row = new AgentKbVlmConfig();
        row.setWorkspaceId(ws);
        row.setSourceType("PLATFORM");
        row.setModelName(modelName);
        row.setEnabled(1);
        return row;
    }

    private AgentModel model(String name) {
        AgentModel m = new AgentModel();
        m.setModelName(name);
        m.setBaseUrl("https://api.platform.com/v1");
        m.setApiKey("sk-platform");
        return m;
    }

    @Test
    void workspaceCustomWinsOverDefault() {
        when(configMapper.selectOne(any())).thenReturn(custom(9L, "https://api.custom.com/v1", "sk-custom"));
        KbVlmConfigResolver.Resolved r = resolver.resolve(kb(9L));
        assertEquals("https://api.custom.com/v1", r.baseUrl());
        assertEquals("sk-custom", r.apiKey(), "CUSTOM 的 Key 应解密后使用");
        assertEquals("WORKSPACE", r.from());
    }

    @Test
    void fallsBackToPlatformDefault() {
        // 工作空间无配置 → 平台默认行
        when(configMapper.selectOne(any())).thenReturn(null, platform(0L, "vision-model"));
        when(modelMapper.selectList(any())).thenReturn(List.of(model("vision-model")));
        KbVlmConfigResolver.Resolved r = resolver.resolve(kb(9L));
        assertEquals("PLATFORM_DEFAULT", r.from());
        assertEquals("sk-platform", r.apiKey(), "PLATFORM 复用平台模型凭证");
    }

    @Test
    void fallsBackToGlobalVlmModel() {
        properties.setVlmModel("vision-model");
        when(configMapper.selectOne(any())).thenReturn(null);
        when(modelMapper.selectList(any())).thenReturn(List.of(model("vision-model")));
        KbVlmConfigResolver.Resolved r = resolver.resolve(kb(9L));
        assertEquals("GLOBAL", r.from());
    }

    @Test
    void disabledRowIsIgnored() {
        AgentKbVlmConfig row = custom(9L, "https://api.custom.com/v1", "sk-custom");
        row.setEnabled(0);
        when(configMapper.selectOne(any())).thenReturn(row, (AgentKbVlmConfig) null);
        assertNull(resolver.resolve(kb(9L)), "禁用行应被跳过且无其他来源");
    }

    @Test
    void platformRowWithMissingModelFallsThrough() {
        when(configMapper.selectOne(any())).thenReturn(platform(9L, "not-exists"), (AgentKbVlmConfig) null);
        when(modelMapper.selectList(any())).thenReturn(List.of());
        assertNull(resolver.resolve(kb(9L)));
    }

    @Test
    void noWorkspaceOnlyUsesDefault() {
        when(configMapper.selectOne(any())).thenReturn(custom(0L, "https://api.custom.com/v1", "sk-default"));
        KbVlmConfigResolver.Resolved r = resolver.resolve(kb(null));
        assertEquals("PLATFORM_DEFAULT", r.from());
        assertEquals("sk-default", r.apiKey());
    }
}
