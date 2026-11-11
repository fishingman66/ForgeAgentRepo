package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * Agent 市场安装记录对象 agent_market_install
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_market_install")
public class AgentMarketInstall extends BaseEntity {

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
     * 安装目标工作空间ID
     */
    private Long workspaceId;

    /**
     * 生成的Agent副本ID
     */
    private Long agentCopyId;

    /**
     * 安装的Listing版本号
     */
    private Integer versionNo;

    /**
     * 资源绑定关系(JSON)
     */
    private String bindingsJson;

    /**
     * 升级前配置快照(一键回退用)
     */
    private String prevSnapshotJson;

    /**
     * 安装人用户ID
     */
    private Long userId;

}
