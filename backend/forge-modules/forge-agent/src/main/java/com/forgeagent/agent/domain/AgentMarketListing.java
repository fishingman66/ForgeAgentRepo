package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 市场条目对象 agent_market_listing
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_market_listing")
public class AgentMarketListing extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 源Agent ID
     */
    private Long agentId;

    /**
     * 源工作空间ID
     */
    private Long workspaceId;

    /**
     * 版本号(每次提交递增)
     */
    private Integer versionNo;

    /**
     * 市场展示名
     */
    private String name;

    /**
     * 分类(general/office/dev/data/life/other)
     */
    private String category;

    /**
     * 图标(emoji或URL)
     */
    private String icon;

    /**
     * 详情说明
     */
    private String description;

    /**
     * 使用说明
     */
    private String usageDoc;

    /**
     * 依赖声明摘要(JSON:模型/工具/知识库)
     */
    private String depsJson;

    /**
     * 版本快照(Agent配置不可变快照)
     */
    private String snapshotJson;

    /**
     * 状态(0待审核 1已上架 2已驳回 3已下架)
     */
    private String status;

    /**
     * 审核/下架理由
     */
    private String reviewReason;

    /**
     * 发布者用户ID
     */
    private Long publisherId;

    /**
     * 安装量
     */
    private Integer installCount;

    /**
     * 评分星数合计
     */
    private Integer ratingSum;

    /**
     * 评分人数
     */
    private Integer ratingCount;

    /**
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;

}
