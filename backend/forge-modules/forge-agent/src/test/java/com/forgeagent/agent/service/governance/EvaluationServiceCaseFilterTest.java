package com.forgeagent.agent.service.governance;

import com.forgeagent.agent.mapper.AgentEvalCaseMapper;
import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测运行只吃「已确认」样本(批次 D2)。
 *
 * <p>回流用例默认是待确认态;如果评测把它们也算进去,未经人工判断的样本会直接
 * 影响基线分,门禁数字就失去意义。迁移 Mapper 后,过滤条件固化在
 * {@code AgentEvalCaseMapper.selectApproved} 的 @Select 注解里(编译期可查),
 * 这里断言服务层确实只走该方法,不再有绕过过滤的取数路径。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class EvaluationServiceCaseFilterTest {

    @Mock private AgentEvalCaseMapper caseMapper;

    private EvaluationService service() {
        // run 的样本检索路径只用 caseMapper, 样本为空时不会走到 chatService
        return new EvaluationService(null, caseMapper, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("D2:只走已确认样本查询,空样本时给出可读原因")
    void runOnlyConsumesApprovedCases() {
        when(caseMapper.selectApproved(anyLong(), anyInt())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> service().run(com.forgeagent.agent.domain.bo.EvalRunStartBo.of(3L, 7L, false)));

        assertTrue(ex.getMessage().contains("已确认"), ex.getMessage());
        // 必须走带 review_status 过滤的专用查询,而不是通用 listByDataset(会带入待确认样本)
        verify(caseMapper).selectApproved(3L, 50);
    }
}
