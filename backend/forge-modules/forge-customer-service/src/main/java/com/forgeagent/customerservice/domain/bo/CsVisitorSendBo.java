package com.forgeagent.customerservice.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 访客发消息业务对象
 *
 * @author ForgeAgent
 */
@Data
public class CsVisitorSendBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "消息内容不能为空")
    private String content;

    /** 客户端幂等 ID */
    private String clientMsgId;

    /** 附件 ID 列表(纯附件消息时 content 可空) */
    private java.util.List<Long> attachmentIds;
}
