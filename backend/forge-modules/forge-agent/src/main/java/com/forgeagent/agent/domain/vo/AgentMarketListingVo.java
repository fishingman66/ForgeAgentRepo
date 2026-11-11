package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentMarketListing;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 市场条目视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMarketListing.class)
public class AgentMarketListingVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long agentId;
    private Long workspaceId;
    private Integer versionNo;
    private String name;
    private String category;
    private String icon;
    private String description;
    private String usageDoc;
    private String depsJson;
    private String status;
    private String reviewReason;
    private Long publisherId;
    private String publisherName;
    private Integer installCount;
    private Integer ratingSum;
    private Integer ratingCount;
    /**
     * 平均评分(0-5,一位小数)
     */
    private Double ratingAvg;
    private Date createTime;
    private Date updateTime;

}
