package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentMarketReview;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 市场评分视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMarketReview.class)
public class AgentMarketReviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long listingId;
    private Long installId;
    private Long userId;
    private Integer stars;
    private String comment;
    private Date createTime;

}
