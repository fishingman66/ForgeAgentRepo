package com.forgeagent.agent.service.governance;

import com.forgeagent.agent.domain.bo.PromptGenerateBo;
import com.forgeagent.agent.domain.vo.PromptGenerateVo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * C4 指令生成的前置校验与失败降级契约。
 *
 * <p>模型调用本身不在此mock(UtilityAgents 为静态构建器);这里锁定的是
 * "参数不全/模型越权/配置残缺一律 ok=false 而非 500",避免前端拿到异常白屏。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PromptAuthorServiceTest {

    @Mock
    private AgentModelMapper modelMapper;
    @Mock
    private ResourceAccessGuard resourceGuard;

    private PromptAuthorService service;

    @BeforeEach
    void setUp() {
        service = new PromptAuthorService(modelMapper, resourceGuard);
    }

    private PromptGenerateBo bo() {
        PromptGenerateBo bo = new PromptGenerateBo();
        bo.setWorkspaceId(900L);
        bo.setModelId(1L);
        bo.setAgentName("售后助手");
        bo.setPurpose("处理退换货咨询并给出可执行的处理步骤");
        return bo;
    }

    @Test
    void rejectsMissingWorkspace() {
        PromptGenerateBo bo = bo();
        bo.setWorkspaceId(null);
        PromptGenerateVo vo = service.generate(bo);
        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("workspaceId"));
    }

    @Test
    void rejectsMissingModel() {
        PromptGenerateBo bo = bo();
        bo.setModelId(null);
        PromptGenerateVo vo = service.generate(bo);
        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("模型"));
    }

    @Test
    void rejectsTooShortPurpose() {
        PromptGenerateBo bo = bo();
        bo.setPurpose("帮忙");
        PromptGenerateVo vo = service.generate(bo);
        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("描述"));
    }

    @Test
    void rejectsUnknownModel() {
        when(modelMapper.selectById(1L)).thenReturn(null);
        PromptGenerateVo vo = service.generate(bo());
        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("模型不存在"));
    }

    @Test
    void rejectsModelFromOtherWorkspace() {
        AgentModel model = model();
        when(modelMapper.selectById(1L)).thenReturn(model);
        org.mockito.Mockito.doThrow(new ServiceException("无权访问该资源"))
            .when(resourceGuard).assertReadableBy(800L, 900L);

        PromptGenerateVo vo = service.generate(bo());

        assertFalse(vo.isOk(), "跨空间取用模型必须失败");
        assertTrue(vo.getMessage().contains("无权访问"));
    }

    @Test
    void rejectsIncompleteModelConfig() {
        AgentModel model = model();
        model.setApiKey(null);
        lenient().when(modelMapper.selectById(1L)).thenReturn(model);

        PromptGenerateVo vo = service.generate(bo());

        assertFalse(vo.isOk());
        assertTrue(vo.getMessage().contains("配置不完整"));
    }

    private AgentModel model() {
        AgentModel model = new AgentModel();
        model.setId(1L);
        model.setWorkspaceId(800L);
        model.setBaseUrl("https://api.example.com/v1");
        model.setApiKey("sk-test");
        model.setModelName("qwen-plus");
        return model;
    }
}
