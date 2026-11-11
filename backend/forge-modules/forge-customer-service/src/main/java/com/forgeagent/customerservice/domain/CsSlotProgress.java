package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 多轮槽位收集进度(P1),对应表 {@code agent_cs_slot_progress}。
 *
 * <p>一个会话一份表单一条;asking 为空表示未开始或已填满。</p>
 */
@Data
@TableName("agent_cs_slot_progress")
public class CsSlotProgress {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String formName;
    private String collectedJson;
    private String asking;
    private Date createTime;
    private Date updateTime;
}
