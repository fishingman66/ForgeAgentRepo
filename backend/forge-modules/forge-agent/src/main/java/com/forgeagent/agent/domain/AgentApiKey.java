package com.forgeagent.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 空间 API Key 对象 agent_api_key(明文仅创建时返回,库存哈希)
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_api_key")
public class AgentApiKey extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属工作空间ID
     */
    private Long workspaceId;

    /**
     * Key 名称
     */
    private String name;

    /**
     * Key 脱敏前缀
     */
    private String keyPrefix;

    /**
     * Key 哈希(SHA-256)
     */
    private String keyHash;

    /**
     * 状态(0正常 1已吊销)
     */
    private String status;

    /**
     * 最后使用时间
     */
    private Date lastUsedTime;

    /**
     * 删除标志(0存在 其他删除)
     */
    @TableLogic
    private Long delFlag;

}
