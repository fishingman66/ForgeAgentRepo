package com.forgeagent.agent.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.common.core.validate.AddGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场发布提交对象
 *
 * @author ForgeAgent
 */
@Data
public class AgentMarketPublishBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 源Agent ID(须为本人已发布的Agent)
     */
    @NotNull(message = "Agent ID不能为空", groups = {AddGroup.class})
    private Long agentId;

    /**
     * 市场展示名
     */
    @NotBlank(message = "市场展示名不能为空", groups = {AddGroup.class})
    @Size(max = 50, message = "市场展示名不能超过 50 字符", groups = {AddGroup.class})
    private String name;

    /**
     * 分类
     */
    @NotBlank(message = "分类不能为空", groups = {AddGroup.class})
    @Pattern(regexp = "^(general|office|dev|data|life|other)$", message = "非法的分类", groups = {AddGroup.class})
    private String category;

    /**
     * 图标(emoji或URL)
     */
    @Size(max = 200, message = "图标不能超过 200 字符", groups = {AddGroup.class})
    private String icon;

    /**
     * 详情说明
     */
    @Size(max = 500, message = "详情说明不能超过 500 字符", groups = {AddGroup.class})
    private String description;

    /**
     * 使用说明
     */
    @Size(max = 500, message = "使用说明不能超过 500 字符", groups = {AddGroup.class})
    private String usageDoc;

}
