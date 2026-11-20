package com.forgeagent.customerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CsTicketEventMapper extends BaseMapper<CsTicketEvent> {

    /**
     * A3:某坐席在指定空间内收到的 @提及(增量拉取,按 id 升序)。
     * join 工单表做空间隔离,避免跨空间读到别人的提及。
     */
    @Select("""
        SELECT e.* FROM agent_cs_ticket_event e
        JOIN agent_cs_ticket t ON t.id = e.ticket_id
        WHERE e.event_type = 'MENTION' AND e.target_user_id = #{userId}
          AND t.workspace_id = #{workspaceId} AND e.id > #{sinceId}
        ORDER BY e.id ASC
        LIMIT #{limit}
        """)
    List<CsTicketEvent> selectMentions(@Param("workspaceId") Long workspaceId,
                                       @Param("userId") Long userId,
                                       @Param("sinceId") Long sinceId,
                                       @Param("limit") int limit);
}
