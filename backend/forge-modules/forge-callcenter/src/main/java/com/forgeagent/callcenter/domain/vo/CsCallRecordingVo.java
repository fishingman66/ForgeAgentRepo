package com.forgeagent.callcenter.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 录音短期签名访问结果视图对象:前端拿到 url 直接播放,不持有任何存储凭据
 *
 * @author ForgeAgent
 */
@Data
public class CsCallRecordingVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 短期签名访问地址(oss presign 或 local-fs HMAC 下载)
     */
    private String url;

    /**
     * 存储对象键(排障用,非云侧 URL)
     */
    private String ossKey;

    /**
     * 签名有效期(秒)
     */
    private Integer expireSeconds;

}
