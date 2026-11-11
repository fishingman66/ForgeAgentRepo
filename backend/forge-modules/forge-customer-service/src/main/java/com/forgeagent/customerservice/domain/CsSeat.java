package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 客服坐席 agent_cs_seat(C1),复用 sys_user 与空间角色。 */
@Data
@TableName("agent_cs_seat")
public class CsSeat {
    @TableId private Long id;
    private String seatGroup, skills, online, role;
    private Long userId, workspaceId;
    private Integer maxLoad, currentLoad;
    private Date createTime, updateTime;
}
