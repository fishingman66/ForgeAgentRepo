package com.forgeagent.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.rag.domain.AgentKbDocAcl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库文档角色授权 Mapper(R5-1)
 *
 * @author ForgeAgent
 */
@Mapper
public interface AgentKbDocAclMapper extends BaseMapper<AgentKbDocAcl> {

    /**
     * 登录用户的角色ID集合(平台角色体系 sys_user_role;跨域只读查询,
     * 避免 rag 模块反向依赖 forge-system)
     */
    @Select("select role_id from sys_user_role where user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);
}
