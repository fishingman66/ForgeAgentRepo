package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsAgentRoute;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 智能路由规则视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsAgentRoute.class)
public class CsAgentRouteVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
