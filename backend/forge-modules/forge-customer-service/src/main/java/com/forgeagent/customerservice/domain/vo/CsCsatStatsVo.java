package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CSAT 满意度统计视图对象(CsatService.stats)
 *
 * @author ForgeAgent
 */
@Data
public class CsCsatStatsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 邀请评价数 */
    private Integer invited;

    /** 已提交评价数 */
    private Integer submitted;

    /** 平均分(0-100 取整) */
    private Integer avgScore;

    /** 回收率(0-1 小数) */
    private Double responseRate;
}
