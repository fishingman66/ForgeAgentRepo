package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.ReleaseGateExecution;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 发布门禁执行记录视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = ReleaseGateExecution.class)
public class ReleaseGateExecutionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long agentId;

    /** 试图发布到的目标版本号 */
    private Integer targetVersion;

    /** passed / blocked */
    private String status;

    private String reason;

    /** "0"/"1" 豁免放行标记 */
    private String waived;

    /** 门禁检查明细 JSON */
    private String checksJson;

    private Date createTime;
}
