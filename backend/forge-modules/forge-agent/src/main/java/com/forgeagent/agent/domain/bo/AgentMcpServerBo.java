package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentMcpServer;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * MCP 服务器业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMcpServer.class, reverseConvertGenerate = false)
public class AgentMcpServerBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "MCP服务器ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 服务器名称
     */
    @NotBlank(message = "MCP服务器名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 描述
     */
    @Size(max = 200, message = "描述不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 传输类型
     */
    @NotBlank(message = "传输类型不能为空", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^(sse|streamable_http)$", message = "传输类型非法", groups = {AddGroup.class, EditGroup.class})
    private String serverType;

    /**
     * MCP服务端地址
     */
    @NotBlank(message = "服务端地址不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 300, message = "地址不能超过 300 字符", groups = {AddGroup.class, EditGroup.class})
    private String url;

    /**
     * 自定义请求头(JSON)
     */
    @Size(max = 5000, message = "请求头过长", groups = {AddGroup.class, EditGroup.class})
    private String headers;

    /**
     * 请求超时(秒)
     */
    private Integer timeoutSeconds;

    /**
     * 工具审批模式(auto/ask/deny)
     */
    @Pattern(regexp = "^(auto|ask|deny)$", message = "审批模式非法", groups = {AddGroup.class, EditGroup.class})
    private String approvalMode;

    /**
     * ask模式审批等待超时(秒)
     */
    private Integer approvalTimeout;

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
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

}
