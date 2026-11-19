package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 建议回收结果视图对象(CsAssistMetricService.recycle)
 *
 * @author ForgeAgent
 */
@Data
public class CsRecycleResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer scanned;

    private Integer recycled;

    private Integer created;
}
