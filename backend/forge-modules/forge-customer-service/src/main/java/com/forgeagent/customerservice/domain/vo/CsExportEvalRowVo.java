package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * badcase 导出评测行视图对象(CsBadcaseService.exportEval)
 *
 * @author ForgeAgent
 */
@Data
public class CsExportEvalRowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long caseId;

    private Long badcaseId;

    private Long conversationId;

    private String question;

    private String expectedAnswer;

    private String source;
}
