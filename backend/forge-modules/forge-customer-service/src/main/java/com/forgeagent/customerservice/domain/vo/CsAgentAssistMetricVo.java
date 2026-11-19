package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsAssistMetricDaily;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 坐席辅助日指标视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsAssistMetricDaily.class)
public class CsAgentAssistMetricVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long workspaceId;
    private Date statDate;
    private Integer suggestionCount;
    private Integer adoptedCount;
    private Integer actionCount;
    private Integer intentCount;
    private Integer recycledCount;
    private Date createTime;
    private Date updateTime;
}
