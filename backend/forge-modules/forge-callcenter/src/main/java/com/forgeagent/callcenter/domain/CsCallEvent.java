package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 通话事件轨迹(V1),只追加。唯一键 (call_id, event_seq, event_type) 承担回调幂等。
 */
@Data
@TableName("agent_cc_call_event")
public class CsCallEvent {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long callId;
    private Long eventSeq;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String actorType;
    private String payload;
    private Date createTime;
}
