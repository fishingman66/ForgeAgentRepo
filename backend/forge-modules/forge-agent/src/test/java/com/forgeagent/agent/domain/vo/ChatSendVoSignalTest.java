package com.forgeagent.agent.domain.vo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 终态:零命中信号口径——绑定了知识库且 KB 命中为 0 才算零命中;
 * 未绑定知识库恒 false(不绑定 KB 的会话谈不上零命中)。
 */
@Tag("dev")
class ChatSendVoSignalTest {

    @Test
    void zeroHitOnlyWhenKbBoundAndNoHits() {
        assertTrue(ChatSendVo.zeroHitOf(true, 0));
        assertFalse(ChatSendVo.zeroHitOf(true, 3));
        assertFalse(ChatSendVo.zeroHitOf(false, 0));
        assertFalse(ChatSendVo.zeroHitOf(false, null));
        // 命中数缺失(异常路径)按未零命中处理,不误伤
        assertFalse(ChatSendVo.zeroHitOf(true, null));
    }
}
