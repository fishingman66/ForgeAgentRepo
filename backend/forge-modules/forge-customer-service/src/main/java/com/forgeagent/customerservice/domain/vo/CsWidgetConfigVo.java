package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

/**
 * A2:公开挂件配置视图(免登录)。
 *
 * <p>只暴露品牌化所需的最小字段,严禁回显 channelSecret / apiKeyId / workspaceId 等内部信息。</p>
 *
 * @author ForgeAgent
 */
@Data
public class CsWidgetConfigVo {

    /** 挂件主题色(#RRGGBB) */
    private String theme = "#2563eb";

    /** 挂件形态:bubble / sidebar */
    private String mode = "bubble";

    /** 开场主动消息,空表示不展示 */
    private String welcome;

    /** 扩展配置 JSON 原文(logo/position/size 等),前端自行解析 */
    private String config;

    /** 是否启用挂件(渠道停用置 false) */
    private boolean enabled = true;
}
