package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentSession;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 会话视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSession.class)
public class AgentSessionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * Agent名称
     */
    private String agentName;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * 是否置顶(0否 1是)
     */
    private String pinned;

    /**
     * 最后消息时间
     */
    private Date lastMsgTime;

    /**
     * 创建时间
     */
    private Date createTime;

}
