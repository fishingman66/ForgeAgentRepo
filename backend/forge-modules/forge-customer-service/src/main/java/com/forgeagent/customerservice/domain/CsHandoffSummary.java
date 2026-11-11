package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 会话交接摘要(P0),对应表 {@code agent_cs_handoff_summary}。
 *
 * <p>一个会话一条(conversation_id 唯一),内容为结构化 JSON;接手工单的坐席先看摘要再看消息。</p>
 */
@Data
@TableName("agent_cs_handoff_summary")
public class CsHandoffSummary {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private Long conversationId;
    private Long ticketId;
    private String summaryJson;
    private Integer sourceVersion;
    private String degraded;
    private Date createTime;
    private Date updateTime;
}
