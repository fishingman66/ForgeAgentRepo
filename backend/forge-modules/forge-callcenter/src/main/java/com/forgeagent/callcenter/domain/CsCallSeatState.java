package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 电话坐席就绪态(V1):按 AICC 工号 work_no 维护,seat/user 绑定关系 V2 回填。
 */
@Data
@TableName("agent_cc_call_seat_state")
public class CsCallSeatState {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long seatId;
    private Long userId;
    private Long workspaceId;
    private String workNo;
    private String extension;
    private String readyState;
    private String pauseReason;
    private Date loginAt;
    private Date logoutAt;
    private Date createTime;
    private Date updateTime;
}
