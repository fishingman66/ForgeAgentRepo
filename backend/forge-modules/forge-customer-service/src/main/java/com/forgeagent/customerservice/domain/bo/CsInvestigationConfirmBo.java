package com.forgeagent.customerservice.domain.bo;

import lombok.Data;

/** B4 调查结论确认入参。 */
@Data
public class CsInvestigationConfirmBo {

    /** true=采纳回填工单,false=驳回 */
    private Boolean approved = false;

    /** 坐席补充说明(写入工单事件,建议不要粘贴报告全文) */
    private String note;
}
