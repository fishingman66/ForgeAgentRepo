package com.forgeagent.rag.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKbZeroHitLog;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识库零命中查询日志视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbZeroHitLog.class)
public class AgentKbZeroHitLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * Agent ID
     */
    private Long agentId;

    /**
     * Agent 名称(展示用,按 agentId 批量解析)
     */
    private String agentName;

    /**
     * 会话 ID
     */
    private Long sessionId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 用户原始问题
     */
    private String originalQuery;

    /**
     * 改写后的检索查询
     */
    private String rewrittenQuery;

    /**
     * 参与检索的知识库ID(逗号分隔)
     */
    private String knowledgeIds;

    /**
     * 创建时间
     */
    private Date createTime;

}
