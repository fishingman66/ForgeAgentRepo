package com.forgeagent.customerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.customerservice.domain.CsTicketEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
          AND COALESCE(e.read_flag, 0) = 0
        ORDER BY e.id ASC
        LIMIT #{limit}
        """)
    List<CsTicketEvent> selectMentions(@Param("workspaceId") Long workspaceId,
                                       @Param("userId") Long userId,
                                       @Param("sinceId") Long sinceId,
                                       @Param("limit") int limit);

    /**
     * A-5:批量标记本人本空间的 @提及已读。
     *
     * <p>事件表没有 workspace_id,必须 JOIN 工单表做空间隔离;target_user_id 也用登录态,
     * 绝不接收前端传入的身份,防止横向越权标记他人提醒。</p>
     */
    @Update("""
        <script>
        UPDATE agent_cs_ticket_event e
        JOIN agent_cs_ticket t ON t.id = e.ticket_id
        SET e.read_flag = 1
        WHERE e.event_type = 'MENTION'
          AND e.target_user_id = #{userId}
          AND t.workspace_id = #{workspaceId}
          AND e.id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int markMentionsRead(@Param("workspaceId") Long workspaceId,
                         @Param("userId") Long userId,
                         @Param("ids") List<Long> ids);
}
