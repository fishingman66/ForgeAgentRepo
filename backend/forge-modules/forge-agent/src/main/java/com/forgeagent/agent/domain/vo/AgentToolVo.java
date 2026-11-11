package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentTool;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工具注册视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentTool.class)
public class AgentToolVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;
    /**
     * 归属工作空间(NULL=平台共享,全空间可见;非空=空间私有,仅归属空间可见)
     */
    private Long workspaceId;


    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具类型(builtin/http/sandbox/sql)
     */
    private String toolType;

    /**
     * 绑定数据源ID(仅sql类型工具)
     */
    private Long datasourceId;

    /**
     * HTTP方法
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
     * 沙箱内存限制(仅 sandbox 类型)
     */
    private String sandboxMemory;

    /**
     * 沙箱CPU限制(仅 sandbox 类型)
     */
    private String sandboxCpus;

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
