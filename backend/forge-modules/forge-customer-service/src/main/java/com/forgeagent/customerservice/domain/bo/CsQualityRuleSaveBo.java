package com.forgeagent.customerservice.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsQualityRule;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 质检规则业务对象(运营台新建/编辑)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsQualityRule.class, reverseConvertGenerate = false)
public class CsQualityRuleSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "规则ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 规则类型(KEYWORD/REGEX)
     */
    @Size(max = 20, message = "规则类型不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String ruleType;

    /**
     * 规则内容(关键词或正则)
     */
    @NotBlank(message = "规则内容不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 500, message = "规则内容不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String pattern;

    /**
     * 命中级别(BLOCK/WARN)
     */
    @Size(max = 10, message = "级别不能超过 10 字符", groups = {AddGroup.class, EditGroup.class})
    private String severity;

    /**
     * 是否启用(0否 1是)
     */
    private String enabled;

    /**
     * 命中扣分
     */
    private Integer scoreDelta;
}
