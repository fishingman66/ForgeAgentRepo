package com.forgeagent.agent.service.governance;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测运行只吃「已确认」样本(批次 D2)。
 *
 * <p>回流用例默认是待确认态;如果评测把它们也算进去,未经人工判断的样本会直接
 * 影响基线分,门禁数字就失去意义。这里用 SQL 断言锁住该过滤条件。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class EvaluationServiceCaseFilterTest {

    @Mock
    private JdbcTemplate jdbc;

    private EvaluationService service() {
        // run 的检索路径只用 jdbc,chatService 传 null 即可(样本为空时不会走到它)
        return new EvaluationService(jdbc, null);
    }

    @Test
    @DisplayName("D2:取样本时过滤待确认用例,空样本时给出可读原因")
    void runOnlyConsumesApprovedCases() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> service().run(3L, 7L, false));

        assertTrue(ex.getMessage().contains("已确认"), ex.getMessage());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        String query = sql.getValue();
        assertTrue(query.contains("review_status"), "样本查询必须按审核态过滤: " + query);
        assertTrue(query.contains("'1'"), "只允许已通过('1')的样本进入评测: " + query);
    }
}
