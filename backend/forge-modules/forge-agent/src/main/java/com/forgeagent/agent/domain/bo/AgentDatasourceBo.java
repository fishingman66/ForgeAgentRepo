package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 问数数据源业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentDatasource.class, reverseConvertGenerate = false)
public class AgentDatasourceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "数据源ID不能为空", groups = {EditGroup.class})
    private Long id;
    /**
     * 归属工作空间(NULL=平台共享,全空间可见;非空=空间私有,仅归属空间可见)
     */
    private Long workspaceId;
    /**
     * 是否创建为平台共享资源(仅超管可选;false 时 workspaceId 必填)
     */
    private Boolean shared;



    /**
     * 数据源名称
     */
    @NotBlank(message = "数据源名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "数据源名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 描述
     */
    @Size(max = 200, message = "描述不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 数据库类型
     */
    @Pattern(regexp = "^mysql$", message = "当前仅支持 mysql 数据源", groups = {AddGroup.class, EditGroup.class})
    private String dbType;

    /**
     * 主机地址
     */
    @NotBlank(message = "主机地址不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 200, message = "主机地址不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String host;

    /**
     * 端口
     */
    @NotNull(message = "端口不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "端口非法", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 65535, message = "端口非法", groups = {AddGroup.class, EditGroup.class})
    private Integer port;

    /**
     * 数据库名
     */
    @NotBlank(message = "数据库名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "数据库名不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String dbName;

    /**
     * 连接用户名
     */
    @NotBlank(message = "连接用户名不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "连接用户名不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String username;

    /**
     * 连接密码(编辑时留空表示不修改)
     */
    @NotBlank(message = "连接密码不能为空", groups = {AddGroup.class})
    @Size(max = 200, message = "连接密码不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String password;

    /**
     * 额外JDBC参数
     */
    @Size(max = 500, message = "额外JDBC参数不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String params;

    /**
     * 单次查询最大返回行数
     */
    @Min(value = 1, message = "最大返回行数至少为 1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 2000, message = "最大返回行数不能超过 2000", groups = {AddGroup.class, EditGroup.class})
    private Integer maxRows;

    /**
     * 查询超时(秒)
     */
    @Min(value = 1, message = "查询超时至少为 1 秒", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 120, message = "查询超时不能超过 120 秒", groups = {AddGroup.class, EditGroup.class})
    private Integer timeoutSeconds;

    /**
     * 可查询表白名单(逗号分隔,空为全部)
     */
    @Size(max = 2000, message = "可查询表白名单不能超过 2000 字符", groups = {AddGroup.class, EditGroup.class})
    private String tableWhitelist;

    /**
     * 是否启用语义层注入(0否 1是)
     */
    private String semanticEnabled;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 备注
     */
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 50)
    private String keyword;

}
