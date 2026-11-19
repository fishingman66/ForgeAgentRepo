package com.forgeagent.customerservice.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsSensitiveWord;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 敏感词业务对象(运营台新建/编辑)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsSensitiveWord.class, reverseConvertGenerate = false)
public class CsSensitiveWordSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "词条ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 敏感词条
     */
    @NotBlank(message = "词条不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "词条不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String word;

    /**
     * 处置级别(BLOCK/MASK/WARN)
     */
    @Size(max = 10, message = "级别不能超过 10 字符", groups = {AddGroup.class, EditGroup.class})
    private String severity;

    /**
     * 备注
     */
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 是否启用(0否 1是)
     */
    private String enabled;
}
