package com.forgeagent.customerservice.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsSeat;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
/**
 * 坐席视图对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsSeat.class)
public class CsSeatVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String seatGroup;
    private String skills;
    private String online;
    private String role;
    private Long userId;
    private Long workspaceId;
    private Integer maxLoad;
    private Integer currentLoad;
    private Date createTime;
    private Date updateTime;
}
