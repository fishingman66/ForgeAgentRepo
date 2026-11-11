package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * Agent 问数数据源对象 agent_datasource
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_datasource")
public class AgentDatasource extends BaseEntity {

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
     * 数据源名称(全局唯一)
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 数据库类型(mysql)
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
     * 连接用户名(须为只读账号)
     */
    private String username;

    /**
     * 连接密码(AES加密存储)
     */
    private String password;

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
     * 语义层版本号(每次语义资产编辑自增,用于缓存失效)
     */
    private Integer semanticVersion;

    /**
     * 表结构快照(注入问数提示词)
     */
    private String schemaSnapshot;

    /**
     * 表结构明细JSON(供 describe 工具按需取用)
     */
    private String schemaTables;

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
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
