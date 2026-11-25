package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 话单补偿留痕(P1-3):已挂机但指标未重算的通话,记录补偿状态与重试次数。
 *
 * <p>无外部推送出口(代码事实),故补偿语义收敛为"幂等重算指标 + 失败留痕 + 退避重试",
 * 不复刻参考项目的外部话单推送。唯一键 (call_id) 防重复补偿。</p>
 */
@Data
@TableName("agent_cc_cdr_compensation")
public class CsCdrCompensation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long callId;

    private Long workspaceId;

    /** 指标归属日(按 ringAt 的自然日,与 CallMetricService 同口径)。 */
    private Date statDate;

    /** PENDING / RETRIED / REBUILT / UNREBUILDABLE。 */
    private String status;

    private Integer retryCount;

    private String error;

    private Date lastRetryAt;

    private Date createTime;

    private Date updateTime;
}
