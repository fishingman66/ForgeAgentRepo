package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 呼损回拨单(C8,方案 docs/07 §三 P2):振铃未接通的通话自动进清单,由运营回拨销单。
 *
 * <p>状态机只有三态且单向:PENDING(待回拨) → CALLED(已回拨) → CLOSED(已关闭);
 * 允许 PENDING 直接 CLOSED(信息已从别处补齐)。非法流转一律拒绝,
 * 避免前端误操作把已回拨的单子退回待办、造成重复外呼。</p>
 */
@Data
@TableName("agent_cc_callback_task")
public class CsCallbackTask {

    /** 待回拨。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已回拨。 */
    public static final String STATUS_CALLED = "CALLED";
    /** 已关闭。 */
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** agent_cc_call.id。 */
    private Long callId;

    private Long workspaceId;
    private String channelCode;
    /** 已 PII 脱敏。 */
    private String callerNumber;

    /** NO_ANSWER(振铃未接) / ABANDONED(排队放弃)。 */
    private String reason;

    private String status;
    private Integer attemptCount;
    private Date lastAttemptAt;
    private Long operatorId;
    private String remark;

    private Date createTime;
    private Date updateTime;
}
