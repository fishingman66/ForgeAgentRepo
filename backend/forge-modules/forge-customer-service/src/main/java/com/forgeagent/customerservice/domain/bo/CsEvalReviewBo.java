package com.forgeagent.customerservice.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 评测回流用例审核业务对象(true=通过,false=驳回)
 *
 * @author ForgeAgent
 */
@Data
public class CsEvalReviewBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** true=确认入评测集,false=驳回 */
    private Boolean approve;
}
