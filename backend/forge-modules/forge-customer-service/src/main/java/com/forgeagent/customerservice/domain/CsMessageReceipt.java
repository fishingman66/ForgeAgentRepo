package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 访客消息幂等回执 agent_cs_message_receipt(C0),clientMsgId 去重。 */
@Data
@TableName("agent_cs_message_receipt")
public class CsMessageReceipt {
    @TableId private Long id;
    private String clientMsgId, acceptance, runId, replyContent;
    private Long sessionId, messageId;
    private Date createTime;
}
