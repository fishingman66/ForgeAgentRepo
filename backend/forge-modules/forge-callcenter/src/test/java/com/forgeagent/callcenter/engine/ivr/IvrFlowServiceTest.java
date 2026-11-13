package com.forgeagent.callcenter.engine.ivr;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 流程 JSON 反序列化与合法性校验单测(C3):
 * 种子与 agent_cc_c3.sql 的 demo_inbound 同构,JSON 改动必须同步 SQL。
 */
@Tag("dev")
class IvrFlowServiceTest {

    private final IvrFlowService service = new IvrFlowService(null);

    private static final String DEMO_FLOW = """
        [
          {"id":"s","type":"start","nextId":"n1"},
          {"id":"n1","type":"say","text":"您好,欢迎致电,请问有什么可以帮您","nextId":"n2"},
          {"id":"n2","type":"answer","prompt":"","timeoutMs":8000,"nextId":"n3","silenceNextId":"n1"},
          {"id":"n3","type":"intention","branches":{"refund":"n4","query_order":"n4"},"otherNextId":"n6"},
          {"id":"n4","type":"agent","noAnswerNextId":"n6"},
          {"id":"n6","type":"say","text":"暂未找到相关答案,已为您转接人工","nextId":"n5"},
          {"id":"n5","type":"transfer","queue":"after_sales"}
        ]""";

    @Test
    void deserializesAllNodeTypes() {
        Map<String, IvrNode> nodes = service.deserialize(DEMO_FLOW);
        assertEquals(7, nodes.size());
        assertInstanceOf(IvrNode.Start.class, nodes.get("s"));
        assertInstanceOf(IvrNode.Say.class, nodes.get("n1"));
        assertEquals("您好,欢迎致电,请问有什么可以帮您", ((IvrNode.Say) nodes.get("n1")).getText());
        assertInstanceOf(IvrNode.Answer.class, nodes.get("n2"));
        assertEquals("n1", ((IvrNode.Answer) nodes.get("n2")).getSilenceNextId());
        assertInstanceOf(IvrNode.Intention.class, nodes.get("n3"));
        assertEquals("n4", ((IvrNode.Intention) nodes.get("n3")).getBranches().get("refund"));
        assertInstanceOf(IvrNode.Agent.class, nodes.get("n4"));
        assertInstanceOf(IvrNode.Transfer.class, nodes.get("n5"));
        assertEquals("after_sales", ((IvrNode.Transfer) nodes.get("n5")).getQueue());
    }

    @Test
    void rejectsFirstNodeNotStart() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deserialize(
            """
            [{"id":"a","type":"say","text":"x","nextId":null}]"""));
        assertEquals("IVR 流程首节点必须为 start", ex.getMessage());
    }

    @Test
    void rejectsDuplicateIds() {
        assertThrows(ServiceException.class, () -> service.deserialize(
            """
            [
              {"id":"s","type":"start","nextId":"a"},
              {"id":"a","type":"hangup"},
              {"id":"a","type":"hangup"}
            ]"""));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(ServiceException.class, () -> service.deserialize("not-json"));
    }

    @Test
    void supportsReceivedAndConditionAndHangupNodes() {
        Map<String, IvrNode> nodes = service.deserialize("""
            [
              {"id":"s","type":"start","nextId":"r"},
              {"id":"r","type":"received","prompt":"请输入工号","endKey":"#","maxDigits":8,"nextId":"c"},
              {"id":"c","type":"condition","expression":"dtmfDigits.length() > 3","trueNextId":"h","falseNextId":"r"},
              {"id":"h","type":"hangup","cause":"NORMAL_CLEARING"}
            ]""");
        assertInstanceOf(IvrNode.Received.class, nodes.get("r"));
        assertEquals("#", ((IvrNode.Received) nodes.get("r")).getEndKey());
        assertInstanceOf(IvrNode.Condition.class, nodes.get("c"));
        assertEquals("dtmfDigits.length() > 3", ((IvrNode.Condition) nodes.get("c")).getExpression());
        assertInstanceOf(IvrNode.Hangup.class, nodes.get("h"));
    }
}
