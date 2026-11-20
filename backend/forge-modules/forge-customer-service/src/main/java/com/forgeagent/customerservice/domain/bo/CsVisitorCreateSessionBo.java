package com.forgeagent.customerservice.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 访客建会话业务对象(全字段可空)
 *
 * @author ForgeAgent
 */
@Data
public class CsVisitorCreateSessionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 已有会话令牌(续聊) */
    private String visitorToken;

    /** 首条消息(可空,仅建会话) */
    private String content;

    /** 访客外部标识(渠道回调链路;为空则从 token 解析) */
    private String externalUserId;

    /** A2 宿主上下文:发起会话的页面地址(只存 http/https,去 query/hash) */
    private String pageUrl;

    /** A2 宿主上下文:订单号(最长 64,超长丢弃) */
    private String orderId;
}
