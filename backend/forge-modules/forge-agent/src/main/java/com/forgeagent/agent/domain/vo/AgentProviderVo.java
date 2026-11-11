package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentProvider;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 模型供应商配置视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentProvider.class)
public class AgentProviderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 供应商编码
     */
    private String providerCode;

    /**
     * 供应商展示名
     */
    private String providerName;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
