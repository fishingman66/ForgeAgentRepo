package com.forgeagent.callcenter.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 通话详情视图对象:主记录 + 事件轨迹 + 转写时间轴,一次返回供详情抽屉渲染
 *
 * @author ForgeAgent
 */
@Data
public class CsCallDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 通话主记录
     */
    private CsCallVo call;

    /**
     * 事件轨迹(按 eventSeq/id 升序)
     */
    private List<CsCallEventVo> events;

    /**
     * 转写分片时间轴(按 id 升序)
     */
    private List<CsCallSegmentVo> segments;

}
