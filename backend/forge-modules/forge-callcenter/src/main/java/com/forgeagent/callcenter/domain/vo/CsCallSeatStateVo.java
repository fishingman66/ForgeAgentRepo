package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallSeatState;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 电话坐席就绪态视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallSeatState.class)
public class CsCallSeatStateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * seatId
     */
    private Long seatId;

    /**
     * userId
     */
    private Long userId;

    /**
     * workspaceId
     */
    private Long workspaceId;

    /**
     * workNo
     */
    private String workNo;

    /**
     * extension
     */
    private String extension;

    /**
     * readyState
     */
    private String readyState;

    /**
     * pauseReason
     */
    private String pauseReason;

    /**
     * loginAt
     */
    private Date loginAt;

    /**
     * logoutAt
     */
    private Date logoutAt;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
