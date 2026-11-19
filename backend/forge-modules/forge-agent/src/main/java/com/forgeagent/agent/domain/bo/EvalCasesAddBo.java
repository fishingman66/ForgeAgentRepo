package com.forgeagent.agent.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 评测样本批量导入业务对象
 *
 * @author ForgeAgent
 */
@Data
public class EvalCasesAddBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "datasetId 不能为空")
    private Long datasetId;

    @Valid
    @NotNull(message = "cases 不能为空")
    private List<CaseItem> cases;

    /**
     * 单条样本(question 必填,其余可空)
     */
    @Data
    public static class CaseItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @NotBlank(message = "样本 question 不能为空")
        private String question;

        private String expectedKeywords;

        private String referenceAnswer;

        /** 期望拒答 */
        private Boolean expectedRefusal;
    }
}
