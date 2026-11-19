package com.forgeagent.customerservice.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 访客转人工业务对象
 *
 * @author ForgeAgent
 */
@Data
public class CsHandoffBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 转人工原因(可空,默认 visitor_request) */
    private String reason;
}
