package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 溢出策略(P0-3):排队超时/队列长度/呼损率触发后转队列或挂机。
 *
 * <p>workspace 为隔离主键;trigger_type 在单空间内唯一(uk_ccoc_ws_trigger)。</p>
 */
@Data
@TableName("agent_cc_overflow_config")
public class CsOverflowConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private String name;

    /** QUEUE_TIMEOUT(等待秒数) / QUEUE_SIZE(队列长度) / ABANDON_RATE(呼损率%)。 */
    private String triggerType;

    /** 阈值:秒 / 条数 / 百分比。 */
    private Integer threshold;

    /** TRANSFER_QUEUE / HANGUP / MARK。 */
    private String action;

    /** 目标队列名 / hangup cause / MARK 说明。 */
    private String actionArg;

    /** 0=启用 1=停用。 */
    private String enabled;

    private Date createTime;

    private Date updateTime;
}
