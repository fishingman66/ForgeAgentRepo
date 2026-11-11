package com.forgeagent.agent.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import com.forgeagent.agent.domain.AgentWorkspace;
import com.forgeagent.agent.domain.vo.AgentWorkspaceVo;
import com.forgeagent.common.mybatis.annotation.DataColumn;
import com.forgeagent.common.mybatis.annotation.DataPermission;
import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 工作空间 Mapper
 *
 * @author ForgeAgent
 */
public interface AgentWorkspaceMapper extends BaseMapperPlus<AgentWorkspace, AgentWorkspaceVo> {

    /**
     * 查询当前用户加入的空间列表(附带我的角色与成员数)
     *
     * @param userId  用户ID
     * @param keyword 名称关键字
     * @return 空间列表
     */
    List<AgentWorkspaceVo> selectMyWorkspaces(@Param("userId") Long userId, @Param("keyword") String keyword);

    /**
     * 分页查询全部空间(平台管理视角,附带所有者昵称与成员数)
     * <p>按角色数据范围过滤:部门管理员仅可见本部门及以下的空间</p>
     *
     * @param page    分页
     * @param keyword 名称关键字
     * @param status  状态
     * @param deptId  归属部门筛选(可选)
     * @return 分页结果
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "w.dept_id"),
        @DataColumn(key = "userName", value = "w.create_by")
    })
    Page<AgentWorkspaceVo> selectWorkspacePage(@Param("page") Page<AgentWorkspace> page,
                                               @Param("keyword") String keyword,
                                               @Param("status") String status,
                                               @Param("deptId") Long deptId);
}
