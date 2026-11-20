package com.forgeagent.agent.service.impl;

import com.forgeagent.agent.domain.bo.AgentModelBo;
import com.forgeagent.agent.domain.vo.AgentModelTestVo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.service.AuditLogHelper;
import com.forgeagent.agentcore.service.AgentConfigCacheService;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * C3-2 BYO-key 连通性测试的入参收敛契约。
 *
 * <p>重点:掩码串不得当作真 Key 使用(回退库内密文);配置不全时返回 ok=false 而不是 5xx。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentModelConnectivityTest {

    @Mock
    private AgentModelMapper modelMapper;
    @Mock
    private AuditLogHelper auditLogHelper;
    @Mock
    private AgentConfigCacheService agentConfigCache;
    @Mock
    private ResourceAccessGuard resourceGuard;

    private AgentModelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentModelServiceImpl(modelMapper, auditLogHelper, agentConfigCache, resourceGuard);
    }

    @Test
    void rejectsIncompleteConfigWithoutId() {
        AgentModelBo bo = new AgentModelBo();
        bo.setName("未填完");
        AgentModelTestVo vo = service.testConnection(bo);
        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("不完整"));
    }

    @Test
    void maskedKeyFallsBackToStoredKeyAndFailsWhenStoredMissing() {
        AgentModel exists = new AgentModel();
        exists.setId(3L);
        exists.setWorkspaceId(900L);
        exists.setBaseUrl("https://api.example.com/v1");
        exists.setModelName("qwen-plus");
        exists.setApiKey(null);
        when(modelMapper.selectById(3L)).thenReturn(exists);

        AgentModelBo bo = new AgentModelBo();
        bo.setId(3L);
        bo.setApiKey("sk-1****");
        AgentModelTestVo vo = service.testConnection(bo);

        // 掩码回退后仍无真 Key → 明确报错,绝不拿掩码去请求
        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("不完整"));
    }

    @Test
    void rejectsUnknownModelId() {
        when(modelMapper.selectById(9L)).thenReturn(null);
        AgentModelBo bo = new AgentModelBo();
        bo.setId(9L);

        assertThrows(ServiceException.class, () -> service.testConnection(bo));
    }
}
