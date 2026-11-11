package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服访客 agent_cs_visitor(C0),不进入 sys_user。 */
@Data
@TableName("agent_cs_visitor")
public class CsVisitor {
    @TableId private Long id;
    private String channelCode, externalUserId, nickname;
    private String consentMemory, consentExport;
    private Date lastActiveAt, erasedAt, createTime;
}
