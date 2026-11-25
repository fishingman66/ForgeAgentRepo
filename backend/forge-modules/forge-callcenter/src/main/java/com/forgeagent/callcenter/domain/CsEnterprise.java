package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 企业域(P0-1):workspace 的计费/配额上层,不改动数据隔离主键。
 *
 * <p>一个 workspace 关联一条企业行(uk_cce_workspace);企业之间用 pid/id_path 构成树,
 * 子企业未配置的配额沿 id_path 从最近祖先继承,仍为空则回退 CallcenterProperties 默认值。</p>
 */
@Data
@TableName("agent_cc_enterprise")
public class CsEnterprise implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 agent_workspace.id,数据隔离仍以 workspace 为准。 */
    private Long workspaceId;

    private String name;

    /** 企业编码,全局唯一。 */
    private String code;

    /** 父企业ID,0=顶层。 */
    private Long pid;

    /** 祖先路径 /a/b/,用于配额继承。 */
    private String idPath;

    /** 0=正常 1=停用。 */
    private String status;

    /** 到期时间,null=永不过期。 */
    private Date expireTime;

    /** 坐席上限,null=继承/默认。 */
    private Integer seatLimit;

    /** 技能组上限,null=继承/默认。 */
    private Integer groupLimit;

    /** 语音渠道上限,null=继承/默认。 */
    private Integer channelLimit;

    /** 并发通话上限,null=继承/默认。 */
    private Integer concurrentLimit;

    /** 月度分钟预算,null=继承/默认。 */
    private Integer budgetMinutes;

    private Date createTime;

    private Date updateTime;
}
