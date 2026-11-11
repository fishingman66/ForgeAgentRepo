package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 市场评分对象 agent_market_review(每个安装记录限评一次)
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_market_review")
public class AgentMarketReview extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 市场条目ID
     */
    private Long listingId;

    /**
     * 安装记录ID
     */
    private Long installId;

    /**
     * 评分用户ID
     */
    private Long userId;

    /**
     * 星数(1-5)
     */
    private Integer stars;

    /**
     * 文字评价(可选)
     */
    private String comment;

}
