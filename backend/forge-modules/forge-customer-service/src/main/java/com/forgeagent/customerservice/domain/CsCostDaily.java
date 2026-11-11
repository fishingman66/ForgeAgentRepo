package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 客服会话日成本(P2),对应表 {@code agent_cs_cost_daily}。
 *
 * <p>token 是实测值,金额是参考值:配置了每千 token 单价才计算,否则留空——
 * 缺价时宁可只报 token,也不写一个假的 0。</p>
 */
@Data
@TableName("agent_cs_cost_daily")
public class CsCostDaily {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private Date statDate;
    private Integer conversationCount;
    private Integer callCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Integer estimatedCount;
    private BigDecimal costAmount;
    private String currency;
    private Integer resolvedCount;
    private Date createTime;
    private Date updateTime;
}
