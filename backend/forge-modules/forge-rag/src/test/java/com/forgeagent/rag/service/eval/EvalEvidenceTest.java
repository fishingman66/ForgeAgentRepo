package com.forgeagent.rag.service.eval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.common.core.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据标注解析单测(R3-1)
 *
 * @author ForgeAgent
 */
@Tag("dev")
class EvalEvidenceTest {

    @BeforeAll
    static void warmUp() {
        // 纯 Mockito/无 Spring 环境下 MP lambda 列名解析需要预热
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            com.forgeagent.rag.domain.AgentKbEvalCase.class);
    }

    @Test
    void parseValidEvidenceList() {
        List<EvalEvidence> list = EvalEvidence.parse(
            "[{\"documentId\":123,\"span\":\"退货政策原文\"},{\"documentId\":456}]");
        assertEquals(2, list.size());
        assertEquals(123L, list.get(0).documentId());
        assertEquals("退货政策原文", list.get(0).span());
        assertEquals(456L, list.get(1).documentId());
        assertEquals(null, list.get(1).span(), "缺 span 视为文档级证据");
    }

    @Test
    void parseBlankReturnsEmpty() {
        assertTrue(EvalEvidence.parse(null).isEmpty());
        assertTrue(EvalEvidence.parse("  ").isEmpty());
    }

    @Test
    void parseRejectsInvalidStructure() {
        assertThrows(ServiceException.class, () -> EvalEvidence.parse("not json"));
        assertThrows(ServiceException.class, () -> EvalEvidence.parse("{\"documentId\":1}"));
        assertThrows(ServiceException.class, () -> EvalEvidence.parse("[{\"span\":\"没有id\"}]"));
        assertThrows(ServiceException.class, () -> EvalEvidence.validate("[]"));
    }

    @Test
    void validateAcceptsGoodJson() {
        EvalEvidence.validate("[{\"documentId\":1,\"span\":\"x\"}]");
        EvalEvidence.validate(null);
    }
}
