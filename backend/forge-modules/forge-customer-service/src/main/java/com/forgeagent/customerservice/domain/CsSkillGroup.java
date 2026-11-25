package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 技能组(P1-1):命名的技能集合 + 匹配方向(参考 SkillGroup.matchType)。
 *
 * <p>用于把一组技能打包供渠道/队列引用;单组技能数上限 10(参考 GroupServiceImpl:71-73)。</p>
 */
@Data
@TableName("agent_cs_skill_group")
public class CsSkillGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private String groupCode;

    private String name;

    /** 组内技能编码,逗号分隔(最多 10 个)。 */
    private String skillCodes;

    /** 匹配方向:ANY 任一命中 / ALL 全部命中。 */
    private String matchType;

    /** 0=启用 1=停用。 */
    private String status;

    private Date createTime;

    private Date updateTime;
}
