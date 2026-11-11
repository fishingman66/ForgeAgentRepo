package com.forgeagent.agent.mapper;

import org.apache.ibatis.annotations.Param;
import com.forgeagent.agent.domain.AgentMarketListing;
import com.forgeagent.agent.domain.vo.AgentMarketListingVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;
import java.util.Map;

/**
 * Agent 市场条目 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentMarketListingMapper extends BaseMapperPlus<AgentMarketListing, AgentMarketListingVo> {

    /**
     * 批量查询用户ID→用户名(发布者展示)
     */
    List<Map<String, Object>> selectPublisherNames(@Param("userIds") List<Long> userIds);

}
