package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 聊天发送结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class ChatSendVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private Long sessionId;

    /**
     * 用户消息
     */
    private AgentMessageVo userMessage;

    /**
     * 助手回复
     */
    private AgentMessageVo assistantMessage;

    /**
     * A1 终态:零命中信号——绑定了知识库且 KB 检索命中为 0(附件命中不计入)。
     * 客服层据此决定缺口归并/badcase,不再各自绕行预检;未绑定知识库时为 false。
     */
    private Boolean zeroHit;

    /**
     * A1 终态:本轮回复的合法引用条数(R4-2 校验通过的 [n] 编号数)。
     * 客服层可据此区分「带引用的检索应答」与「模型自由发挥」。
     */
    private Integer citationCount;

    /** 零命中信号计算口径:KB 绑定且命中数为 0;未绑定知识库恒 false。 */
    public static boolean zeroHitOf(boolean kbBound, Integer kbHitCount) {
        return kbBound && kbHitCount != null && kbHitCount == 0;
    }
}
