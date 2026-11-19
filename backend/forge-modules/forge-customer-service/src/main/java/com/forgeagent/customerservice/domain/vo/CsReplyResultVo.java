package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 坐席回复结果视图对象(CsTicketService.reply)
 *
 * @author ForgeAgent
 */
@Data
public class CsReplyResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long messageId;

    private Long ticketId;

    /** 坐席回复全文 */
    private String content;

    /** 处理后工单状态(仍为 PROCESSING) */
    private String state;
}
