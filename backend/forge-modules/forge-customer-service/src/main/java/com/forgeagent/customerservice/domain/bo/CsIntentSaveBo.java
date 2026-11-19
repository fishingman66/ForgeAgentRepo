package com.forgeagent.customerservice.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsIntent;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 意图配置业务对象(运营台新建/编辑)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsIntent.class, reverseConvertGenerate = false)
public class CsIntentSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "意图ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 意图编码(唯一业务键,大写)
     */
    @NotBlank(message = "意图编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "意图编码不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String intentCode;

    /**
     * 意图名称
     */
    @NotBlank(message = "意图名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "意图名称不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String intentName;

    /**
     * 命中关键词(逗号分隔)
     */
    @NotBlank(message = "关键词不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 500, message = "关键词不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String keywords;

    /**
     * 规则类型(KEYWORD/SEMANTIC)
     */
    @Size(max = 20, message = "规则类型不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String ruleType;

    /**
     * 关联知识库 ID(逗号分隔;SEMANTIC 规则用)
     */
    @Size(max = 500, message = "知识库ID串不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String kbIds;

    /**
     * 优先级(小者优先)
     */
    private Integer priority;

    /**
     * 是否启用(0否 1是)
     */
    private String enabled;
}
