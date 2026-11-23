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
    /** A-3:原始 RFC Message-ID,人工回信回填线程头;老数据为 null 时退化为按地址回信。 */
    private String rawMessageId;
    private Long sessionId, messageId;
    private Date createTime;
}
