package com.forgeagent.callcenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forgeagent.callcenter.domain.CsCallInsight;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通话洞察 Mapper(C4);幂等依靠表级唯一键 uk_ccci_call_kind_key。
 *
 * <p>按唯一键单行查询做成显式参数方法(而非 LambdaQueryWrapper):
 * 调用点语义一目了然,单测也能按实参打桩,不必解析 wrapper 的私有条件栈。</p>
 */
public interface CsCallInsightMapper extends BaseMapper<CsCallInsight> {

    /** 按唯一键 (call_id, kind, item_key) 取一行;不存在返回 null。 */
    @Select("SELECT * FROM agent_cc_call_insight"
        + " WHERE call_id = #{callId} AND kind = #{kind} AND item_key = #{itemKey} LIMIT 1")
    CsCallInsight selectByKey(@Param("callId") Long callId,
                              @Param("kind") String kind,
                              @Param("itemKey") String itemKey);
}
