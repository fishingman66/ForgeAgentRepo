package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 坐席辅助推荐记录(A1),对应表 {@code agent_cs_seat_suggestion}。
 *
 * <p>一条推荐一次落库:items / actions 为候选 JSON 快照（payload 只增不改的演进约定见
 * 坐席辅助方案 4.4 节）;adopted / adopt_action 承担采纳埋点,是 A5 效果度量的数据源。</p>
 */
@Data
@TableName("agent_cs_seat_suggestion")
public class CsSeatSuggestion {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private Long ticketId;
    private Long messageId;
    private String intent;
    private String items;
    private String actions;
    /** 触发来源(A2):text 文本消息 / voice 语音转写。 */
    private String source;
    /** 语音来源的通话主键(A2),文本场景为空;用于按通话追溯与节流复盘。 */
    private Long callId;
    /** 触发原文(A5):文本消息或转写分片,零采纳回流时作为待审 FAQ 的问题。 */
    private String triggerText;
    /** 是否已零采纳回流(A5):1 已回流,避免重复扫同一条推荐。 */
    private String recycled;
    private Double topScore;
    private String adopted;
    private String adoptAction;
    private Date createTime;
}
