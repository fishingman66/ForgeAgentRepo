package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 技能定义(P1-1):结构化技能目录,workspace 内 skillCode 唯一。
 *
 * <p>与 AI agent 的 AgentSkill(声明式技能包)无关;这是坐席路由用的技能标签。</p>
 */
@Data
@TableName("agent_cs_skill")
public class CsSkill implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    /** 技能编码(空间内唯一),如 refund/tech/invoice。 */
    private String skillCode;

    /** 技能名称。 */
    private String name;

    /** 0=启用 1=停用。 */
    private String status;

    private Date createTime;

    private Date updateTime;
}
