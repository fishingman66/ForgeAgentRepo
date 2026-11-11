package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentMcpServer;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * MCP 服务器视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMcpServer.class)
public class AgentMcpServerVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 服务器名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 传输类型(sse/streamable_http)
     */
    private String serverType;

    /**
     * MCP服务端地址
     */
    private String url;

    /**
     * 自定义请求头(JSON)
     */
    private String headers;

    /**
     * 请求超时(秒)
     */
    private Integer timeoutSeconds;

    /**
     * 工具审批模式(auto/ask/deny)
     */
    private String approvalMode;

    /**
     * ask模式审批等待超时(秒)
     */
    private Integer approvalTimeout;

    /**
     * 最近一次连接测试发现的工具数
     */
    private Integer toolCount;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
