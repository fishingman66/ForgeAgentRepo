package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 知识库零命中查询日志 agent_kb_zero_hit_log
 * (对话检索零命中时落表,用于运营发现知识缺口、反哺内容建设)
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_zero_hit_log")
public class AgentKbZeroHitLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Agent ID
     */
    private Long agentId;

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
     * 改写后的检索查询(未改写时与原始问题一致)
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
