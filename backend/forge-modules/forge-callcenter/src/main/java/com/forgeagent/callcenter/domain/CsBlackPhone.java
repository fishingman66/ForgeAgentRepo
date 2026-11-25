package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 黑名单(P2-1):前缀匹配 + 呼叫方向 + 生效时段,对齐参考 BlackPhone。
 *
 * <p><b>回调字段边界</b>:{@code callbackUrl} 仅作为配置留存,本实现<b>不发起外部 HTTP</b>
 * (FR 红线 + 无出向出口),命中只做拦截与留痕。是否启用回调待资质/安全评审后再定。</p>
 */
@Data
@TableName("agent_cc_black_phone")
public class CsBlackPhone implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    /** 号码前缀(最长前缀优先匹配)。 */
    private String numPrefix;

    /** 呼叫方向:0 呼入 / 1 呼出 / 2 双向。 */
    private Integer callDirection;

    /** 生效时段 HH:mm-HH:mm,空=全天。 */
    private String schedule;

    /** 回调地址(仅留存,不发起请求)。 */
    private String callbackUrl;

    /** 命中原因(运营留存)。 */
    private String reason;

    /** 0=启用 1=停用。 */
    private String status;

    private Date createTime;

    private Date updateTime;
}
