package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * SSE 事件重放条目视图对象(CsVisitorController.events)
 *
 * @author ForgeAgent
 */
@Data
public class CsEventItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 单调递增序号(断线续传游标) */
    private Long id;

    /** 事件名(run_id/team_delta/wf_start/...) */
    private String event;

    /** 事件载荷(JSON 串) */
    private String data;
}
