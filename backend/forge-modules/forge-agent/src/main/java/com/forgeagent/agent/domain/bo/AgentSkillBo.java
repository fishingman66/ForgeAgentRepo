package com.forgeagent.agent.domain.bo;

import com.forgeagent.agent.domain.AgentSkill;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;
import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 技能包业务对象(08方案 / A1)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentSkill.class, reverseConvertGenerate = false)
public class AgentSkillBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "技能ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 归属工作空间(NULL=平台共享)
     */
    private Long workspaceId;

    /**
     * 是否创建为平台共享技能(仅超管可选;false 时 workspaceId 必填)
     */
    private Boolean shared;

    /**
     * 技能名称
     */
    @NotBlank(message = "技能名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 一行摘要(懒加载目录展示用)
     */
    @NotBlank(message = "技能摘要不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "摘要不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 操作手册(Markdown)
     */
    @Size(max = 20000, message = "操作手册不能超过 20000 字符", groups = {AddGroup.class, EditGroup.class})
    private String instructions;

    /**
     * 引用平台工具ID列表(逗号分隔)
     */
    @Size(max = 500, message = "工具引用不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String toolIds;

    /**
     * 引用MCP服务器ID列表(逗号分隔)
     */
    @Size(max = 500, message = "MCP引用不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String mcpIds;

    /**
     * 引用知识库ID列表(逗号分隔)
     */
    @Size(max = 500, message = "知识库引用不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String knowledgeIds;

    /**
     * 加载后追加的提示词片段
     */
    @Size(max = 2000, message = "提示词片段不能超过 2000 字符", groups = {AddGroup.class, EditGroup.class})
    private String promptFragment;

    /**
     * 状态
     */
    @Pattern(regexp = "^[01]$", message = "状态非法", groups = {EditGroup.class})
    private String status;

    /**
     * 备注
     */
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 查询关键字(列表页)
     */
    @NotBlank(message = "关键字不能为空", groups = {QueryGroup.class})
    private String keyword;
}
