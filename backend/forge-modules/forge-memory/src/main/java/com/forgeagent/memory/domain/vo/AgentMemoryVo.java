package com.forgeagent.memory.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.memory.domain.AgentMemory;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Agent 长期记忆视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMemory.class)
public class AgentMemoryVo implements Serializable {

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
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * 事实键(稳定排序键;M4 检索结果透出,便于与注入顺序对照)
     */
    private String factKey;

    /**
     * 重要性 1-10
     */
    private Integer importance;

    /**
     * 来源会话ID
     */
    private Long sourceSessionId;

    /**
     * 创建时间
     */
    private Date createTime;

}
