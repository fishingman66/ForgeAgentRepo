package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 工具注册对象 agent_tool
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_tool")
public class AgentTool extends BaseEntity {

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
     * 工具名称(全局唯一)
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具类型(builtin内置/http外部接口/sandbox沙箱代码执行/sql问数只读查询)
     */
    private String toolType;

    /**
     * 绑定数据源ID(仅sql类型工具)
     */
    private Long datasourceId;

    /**
     * HTTP方法(GET/POST/PUT/DELETE)
     */
    private String httpMethod;

    /**
     * HTTP接口地址
     */
    private String httpUrl;

    /**
     * HTTP请求头(JSON)
     */
    private String httpHeaders;

    /**
     * HTTP请求参数模板(JSON)
     */
    private String httpParams;

    /**
     * 沙箱镜像(仅 sandbox 类型)
     */
    private String sandboxImage;

    /**
     * 沙箱执行超时(秒,仅 sandbox 类型)
     */
    private Integer sandboxTimeout;

    /**
     * 沙箱内存限制(如 128m,仅 sandbox 类型)
     */
    private String sandboxMemory;

    /**
     * 沙箱CPU限制(如 0.5,仅 sandbox 类型)
     */
    private String sandboxCpus;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 审批模式(auto自动执行/ask需人工审批/deny拒绝)
     */
    private String approvalMode;

    /**
     * ask模式审批等待超时(秒)
     */
    private Integer approvalTimeout;

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
