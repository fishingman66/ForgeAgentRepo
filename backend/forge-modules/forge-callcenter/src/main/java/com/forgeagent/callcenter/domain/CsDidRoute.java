package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * DID 字冠路由(P2-2):对齐参考 cc_route_call + RouteGetway 的号码变换语义。
 *
 * <p><b>匹配语义（按真实代码，非方案文档臆测）</b>：{@code called.contains(route)} 任意位置包含,
 * `*` 整串相等作 catch-all,按 length(route_num) desc 取第一条命中。
 * <b>变换语义</b>：真实生效的是 callerPrefix/calledPrefix 前缀拼接,
 * callerChange/calledChange 在参考项目是死字段,不实现。</p>
 */
@Data
@TableName("agent_cc_did_route")
public class CsDidRoute implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    /** 字冠/feature code:任意位置包含匹配,`*` 为兜底。 */
    private String routeNum;

    /** 命中后的目标路由码(队列/IVR route_code),空=仅做号码变换。 */
    private String target;

    /** 主叫显号前缀拼接(对齐 RouteGetway.callerPrefix)。 */
    private String callerPrefix;

    /** 被叫前缀拼接(对齐 RouteGetway.calledPrefix)。 */
    private String calledPrefix;

    /** 0=启用 1=停用。 */
    private String status;

    private String description;

    private Date createTime;

    private Date updateTime;
}
