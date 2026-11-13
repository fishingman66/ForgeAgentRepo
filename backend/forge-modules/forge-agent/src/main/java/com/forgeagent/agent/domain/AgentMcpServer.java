package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * MCP 服务器注册对象 agent_mcp_server
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_mcp_server")
public class AgentMcpServer extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 归属工作空间(NULL=平台共享,全空间可见;非空=空间私有,仅归属空间可见)
     */
    private Long workspaceId;

    /**
     * 服务器名称(同一空间内唯一)
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
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
