package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 坐席-技能关联(P1-1):多对多,带等级 rank(参考 SkillAgent.rankValue)。
 *
 * <p>唯一键 (workspace_id, seat_id, skill_code) 防重;写入时同步投影回
 * CsSeat.skills 逗号串(含 skill:rank),保证老读路径零迁移。</p>
 */
@Data
@TableName("agent_cs_seat_skill")
public class CsSeatSkill implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private Long seatId;

    private String skillCode;

    /** 技能等级,数值越大越熟练(参考 SkillAgent.rankValue)。 */
    private Integer rank;

    private Date createTime;

    private Date updateTime;
}
