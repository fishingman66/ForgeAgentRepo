package com.forgeagent.customerservice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 客服多 Agent 路由(P3),对应表 {@code agent_cs_agent_route}。
 *
 * <p>把「意图」映射到「专家 Agent」:退款走售后、物流走订单。渠道编码为空表示对该空间
 * 所有渠道生效,用于「同一套路由铺到全部入口」的常见配置。</p>
 */
@Data
@TableName("agent_cs_agent_route")
public class CsAgentRoute {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private String channelCode;
    private String intentCode;
    private Long agentId;
    private Integer priority;
    private String enabled;
    private Date createTime;
    private Date updateTime;
}
