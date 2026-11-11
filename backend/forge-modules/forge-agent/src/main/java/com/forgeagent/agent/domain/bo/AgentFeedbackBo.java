package com.forgeagent.agent.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息反馈查询业务对象(UX-02 反馈管理)
 *
 * @author ForgeAgent
 */
@Data
public class AgentFeedbackBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * 反馈类型(like/dislike)
     */
    private String feedback;

    /**
     * 反馈时间起(yyyy-MM-dd HH:mm:ss)
     */
    private String beginTime;

    /**
     * 反馈时间止(yyyy-MM-dd HH:mm:ss)
     */
    private String endTime;

}
