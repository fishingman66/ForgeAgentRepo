package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentTool;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工具注册业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentTool.class, reverseConvertGenerate = false)
public class AgentToolBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "工具ID不能为空", groups = {EditGroup.class})
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
     * 工具名称
     */
    @NotBlank(message = "工具名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "工具名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 工具描述
     */
    @Size(max = 200, message = "工具描述不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 工具类型(builtin/http/sandbox/sql)
     */
    @NotBlank(message = "工具类型不能为空", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^(builtin|http|sandbox|sql)$", message = "工具类型非法", groups = {AddGroup.class, EditGroup.class})
    private String toolType;

    /**
     * 绑定数据源ID(仅sql类型工具)
     */
    private Long datasourceId;

    /**
     * HTTP方法
     */
    @Pattern(regexp = "^(GET|POST|PUT|DELETE)$", message = "HTTP方法非法", groups = {AddGroup.class, EditGroup.class})
    private String httpMethod;

    /**
     * HTTP接口地址
     */
    @Size(max = 300, message = "HTTP接口地址不能超过 300 字符", groups = {AddGroup.class, EditGroup.class})
    private String httpUrl;

    /**
     * HTTP请求头(JSON)
     */
    @Size(max = 5000, message = "HTTP请求头过长", groups = {AddGroup.class, EditGroup.class})
    private String httpHeaders;

    /**
     * HTTP请求参数模板(JSON)
     */
    @Size(max = 5000, message = "HTTP请求参数过长", groups = {AddGroup.class, EditGroup.class})
    private String httpParams;

    /**
     * 沙箱镜像(仅 sandbox 类型)
     */
    @Size(max = 200, message = "沙箱镜像不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String sandboxImage;

    /**
     * 沙箱执行超时(秒,仅 sandbox 类型)
     */
    @Min(value = 1, message = "沙箱超时须大于 0", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 300, message = "沙箱超时不能超过 300 秒", groups = {AddGroup.class, EditGroup.class})
    private Integer sandboxTimeout;

    /**
     * 沙箱内存限制(仅 sandbox 类型)
     */
    @Size(max = 20, message = "沙箱内存限制不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String sandboxMemory;

    /**
     * 沙箱CPU限制(仅 sandbox 类型)
     */
    @Size(max = 10, message = "沙箱CPU限制不能超过 10 字符", groups = {AddGroup.class, EditGroup.class})
    private String sandboxCpus;

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
