package com.forgeagent.customerservice.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsKnowledgeAnswer;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识答案业务对象(运营台人工补录/编辑)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsKnowledgeAnswer.class, reverseConvertGenerate = false)
public class CsKnowledgeAnswerSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "答案ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 关联知识缺口 ID(补录时必填)
     */
    @NotNull(message = "知识缺口不能为空", groups = {AddGroup.class})
    private Long knowledgeId;

    /**
     * 问题
     */
    @NotBlank(message = "问题不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 500, message = "问题不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String question;

    /**
     * 答案
     */
    @NotBlank(message = "答案不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 2000, message = "答案不能超过 2000 字符", groups = {AddGroup.class, EditGroup.class})
    private String answer;
}
