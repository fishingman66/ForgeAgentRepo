package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * Agent 会话对象 agent_session
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_session")
public class AgentSession extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 会话所属用户ID
     */
    private Long userId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * L2 滚动摘要:summaryUptoMsgId 及之前的对话要点(P3 / M1)
     */
    private String summaryText;

    /**
     * L2 摘要水位:最后一条已压缩消息的 id,0 表示尚未生成摘要(P3 / M1)
     */
    private Long summaryUptoMsgId;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * 是否置顶(0否 1是)
     */
    private String pinned;

    /**
     * 是否调试临时会话(0否 1是,UX-01);调试会话不进会话列表,30 分钟无活动自动清理
     */
    private Integer temp;

    /**
     * 最后消息时间
     */
    private Date lastMsgTime;

    /**
     * 客服场景(C0):会话场景 console/cs;存量数据默认 console,零行为变更。
     */
    private String scene;

    /** 客服场景(C0):访客ID。 */
    private Long visitorId;

    /** 客服场景(C0):渠道编码。 */
    private String channelCode;

    /** 客服场景(C0):当前工单ID。 */
    private Long csTicketId;

    /**
     * 客服场景(S0):调用方空间ID,仅在本轮对话链路内传递,不落库。
     * prepareRound 写入,buildRound 用它走 ResourceAccessGuard 纯函数空间校验。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long callerWorkspaceId;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
