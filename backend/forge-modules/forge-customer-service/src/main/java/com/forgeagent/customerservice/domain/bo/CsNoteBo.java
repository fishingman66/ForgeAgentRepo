package com.forgeagent.customerservice.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工单备注业务对象(resolve/reject 共用;字段可空)
 *
 * @author ForgeAgent
 */
@Data
public class CsNoteBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 处理备注 */
    private String note;
}
