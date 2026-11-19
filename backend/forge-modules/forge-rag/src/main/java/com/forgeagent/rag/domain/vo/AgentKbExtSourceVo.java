package com.forgeagent.rag.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKbExtSource;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 知识库外部源视图对象(R5-4)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbExtSource.class)
public class AgentKbExtSourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属知识库ID
     */
    private Long knowledgeId;

    /**
     * URL / S3 / CONFLUENCE
     */
    private String sourceType;

    /**
     * 外部源名称
     */
    private String name;

    /**
     * 来源配置 JSON(URL 为 {"url":...});凭据不入库明文
     */
    private String configJson;

    /**
     * 同步状态:IDLE / SYNCING / FAILED
     */
    private String status;

    /**
     * 最近同步时间
     */
    private Date lastSyncAt;

    /**
     * 最近一次同步错误
     */
    private String lastError;

    /**
     * 创建时间
     */
    private Date createTime;

}
