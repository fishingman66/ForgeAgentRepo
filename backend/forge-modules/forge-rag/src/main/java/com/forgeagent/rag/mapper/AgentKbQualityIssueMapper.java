package com.forgeagent.rag.mapper;

import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.rag.domain.AgentKbQualityIssue;
import com.forgeagent.rag.domain.vo.AgentKbQualityIssueVo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库质量问题 Mapper
 *
 * @author ForgeAgent
 */
@Mapper
public interface AgentKbQualityIssueMapper extends BaseMapperPlus<AgentKbQualityIssue, AgentKbQualityIssueVo> {
}
