package com.forgeagent.agent.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.forgeagent.agent.domain.AgentTokenUsage;
import com.forgeagent.agent.domain.vo.AgentTokenUsageVo;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;
import java.util.Map;

/**
 * Token 用量明细 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentTokenUsageMapper extends BaseMapperPlus<AgentTokenUsage, AgentTokenUsageVo> {

    /**
     * 查询用户昵称(用量明细展示用)
     */
    @Select("select nick_name from sys_user where user_id = #{userId} and del_flag = '0'")
    String selectNickNameById(@Param("userId") Long userId);

    /**
     * 批量查询用户昵称,返回 userId → nickName 映射
     */
    @Select("<script>select user_id as userId, nick_name as nickName from sys_user "
        + "where del_flag = '0' and user_id in "
        + "<foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectNickNamesByIds(@Param("userIds") List<Long> userIds);
}
