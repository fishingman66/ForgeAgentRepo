package com.forgeagent.agent.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentDatasource;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 问数数据源视图对象(不含连接密码)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentDatasource.class)
public class AgentDatasourceVo implements Serializable {

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
     * 数据源名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 数据库类型
     */
    private String dbType;

    /**
     * 主机地址
     */
    private String host;

    /**
     * 端口
     */
    private Integer port;

    /**
     * 数据库名
     */
    private String dbName;

    /**
     * 连接用户名
     */
    private String username;

    /**
     * 额外JDBC参数
     */
    private String params;

    /**
     * 单次查询最大返回行数
     */
    private Integer maxRows;

    /**
     * 查询超时(秒)
     */
    private Integer timeoutSeconds;

    /**
     * 可查询表白名单(逗号分隔,空为全部)
     */
    private String tableWhitelist;

    /**
     * 是否启用语义层注入(0否 1是)
     */
    private String semanticEnabled;

    /**
     * 语义层版本号
     */
    private Integer semanticVersion;

    /**
     * 表结构快照(仅用于实体映射,不对外序列化)
     */
    @JsonIgnore
    private String schemaSnapshot;

    /**
     * 表结构快照规模(字符数,0 表示未同步)
     */
    private Long schemaSize;

    /**
     * 表结构最近同步时间
     */
    private Date schemaSyncedAt;

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
