package com.forgeagent.memory.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 记忆治理单次执行结果视图对象:各步的处理量
 *
 * @author ForgeAgent
 */
@Data
public class MemoryGovernanceResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息归档条数(超出保留天数的历史消息)
     */
    private Long archivedMessages;

    /**
     * TTL 过期归档条数(显式 expire_at + ttl-days 隐式过期)
     */
    private Long ttlArchived;

    /**
     * 配额淘汰条数(超限 (agent, 用户) 组按评分淘汰)
     */
    private Long quotaEvicted;

}
