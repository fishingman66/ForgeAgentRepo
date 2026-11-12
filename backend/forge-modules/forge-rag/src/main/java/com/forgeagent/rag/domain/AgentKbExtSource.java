package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库外部源 agent_kb_ext_source(R5-4)
 *
 * @author ForgeAgent
 */
@Data
@TableName("agent_kb_ext_source")
public class AgentKbExtSource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long knowledgeId;

    /**
     * URL / S3 / CONFLUENCE
     */
    private String sourceType;

    private String name;

    /**
     * 来源配置 JSON(URL 为 {"url":...});凭据不入库明文
     */
    private String configJson;

    /**
     * IDLE / SYNCING / FAILED
     */
    private String status;

    private Date lastSyncAt;

    private String lastError;

    private Date createTime;
}
