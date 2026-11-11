package com.forgeagent.agent.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.agent.domain.AgentMarketInstall;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 市场安装记录视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentMarketInstall.class)
public class AgentMarketInstallVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long listingId;
    private String listingName;
    private String listingIcon;
    private Long workspaceId;
    private String workspaceName;
    private Long agentCopyId;
    private String agentCopyName;
    private Integer versionNo;
    /**
     * Listing 当前最新版本号(大于 versionNo 则可升级)
     */
    private Integer latestVersionNo;
    /**
     * 是否已评分
     */
    private Boolean rated;
    private Long userId;
    private Date createTime;

}
