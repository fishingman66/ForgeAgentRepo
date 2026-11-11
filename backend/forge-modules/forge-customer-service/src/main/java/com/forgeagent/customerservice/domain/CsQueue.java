package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 排队项 agent_cs_queue(C1),ACD 分配的输入。 */
@Data
@TableName("agent_cs_queue")
public class CsQueue {
    @TableId private Long id;
    private String requiredSkill, priority, state;
    private Long ticketId, workspaceId;
    private Integer escalateCount;
    private Date enqueuedAt, deadlineAt, createTime;
}
