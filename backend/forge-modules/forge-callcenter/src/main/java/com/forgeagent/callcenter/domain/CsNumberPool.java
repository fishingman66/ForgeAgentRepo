package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 号码池(P2-1,仅数据模型):对齐参考 CompanyPhone 的号码类型口径。
 *
 * <p><b>FR 红线</b>:只存号码资源元数据,不接真实 PSTN/不含 originate 下发。
 * V1 生产链路号码仍由回调事件带入(NoopNumberProvider);本表供资质到位后的 DB Provider 读取。</p>
 */
@Data
@TableName("agent_cc_number_pool")
public class CsNumberPool implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    /** 号码(号码资源,生产环境应加密或脱敏存储)。 */
    private String number;

    /** 类型:1 呼入服务号 / 2 主叫显号 / 3 被叫显号(对齐 CompanyPhone.type)。 */
    private Integer type;

    /** 关联渠道编码(可空,空=空间通用)。 */
    private String channelCode;

    /** 中继引用(仅元数据,不触发绑定动作)。 */
    private String trunkRef;

    /** 0=空闲 1=已分配 2=停用。 */
    private String status;

    private String description;

    private Date createTime;

    private Date updateTime;
}
