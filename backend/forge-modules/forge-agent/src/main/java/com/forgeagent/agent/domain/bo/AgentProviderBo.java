package com.forgeagent.agent.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.agent.domain.AgentProvider;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型供应商配置业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentProvider.class, reverseConvertGenerate = false)
public class AgentProviderBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "供应商ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 供应商编码(小写字母/数字/下划线)
     */
    @NotBlank(message = "供应商编码不能为空", groups = {AddGroup.class})
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,29}$", message = "供应商编码须为 2-30 位小写字母/数字/下划线,且以字母开头",
        groups = {AddGroup.class})
    private String providerCode;

    /**
     * 供应商展示名
     */
    @NotBlank(message = "供应商名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 50, message = "供应商名称不能超过 50 字符", groups = {AddGroup.class, EditGroup.class})
    private String providerName;

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
