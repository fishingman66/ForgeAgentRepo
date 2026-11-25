package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 坐席状态时长轨迹(P1-2):每次就绪态变化落一条,含前态/后态/持续时长。
 *
 * <p><b>分表口径收敛</b>:参考项目 MyBatis XML 有 insertMonthSelective 动态表名,
 * ForgeAgent 全仓固定 @TableName(无动态表名设施),故采用单表 + {@code month} 归档键
 * (yyyy-MM),KEY(idx_ws_month) 支撑按月归档/清理;"分表路由"体现为归档键的确定性计算。</p>
 */
@Data
@TableName("agent_cc_agent_state_log")
public class CsAgentStateLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归档键 yyyy-MM(由状态变更时间确定,跨月落各自的键)。 */
    private String month;

    private Long workspaceId;

    /** AICC 工号(业务唯一键,与 CsCallSeatState 同口径)。 */
    private String workNo;

    private Long seatId;

    private String beforeState;

    private String state;

    /** 本次状态持续秒数(前态开始 → 本次变更)。 */
    private Integer durationSec;

    /** 事件时间(厂商 eventGenTime 归一化后的毫秒)。 */
    private Long eventTimeMs;

    private Date createTime;
}
