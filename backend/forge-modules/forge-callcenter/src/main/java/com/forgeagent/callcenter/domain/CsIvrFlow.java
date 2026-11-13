package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * IVR 流程定义(C3):节点 JSON 数组存 flow longtext,建模对齐 SmartCall ivr_call_route。
 */
@Data
@TableName("agent_cc_ivr_flow")
public class CsIvrFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String routeCode;

    private String routeName;

    private Long workspaceId;

    /** 1=呼入 2=呼出。 */
    private Integer process;

    /** 节点 JSON 数组,首节点 type=start。 */
    private String flow;

    private String status;

    private Long delFlag;

    private Date createTime;

    private Date updateTime;
}
