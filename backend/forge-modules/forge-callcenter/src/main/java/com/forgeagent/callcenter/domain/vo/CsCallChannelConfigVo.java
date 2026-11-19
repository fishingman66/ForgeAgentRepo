package com.forgeagent.callcenter.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 语音渠道配置视图对象。
 *
 * <p>刻意不含 voiceConfigJson(ESL/媒体参数含接入细节)与 delFlag(逻辑删标记):
 * 前者不由运营入口下发,后者属内部字段。callbackSecret 保留,但只承载
 * "__SET__" 置位标记或 null,真实密文永不出现在响应里。</p>
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallChannelConfig.class)
public class CsCallChannelConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 渠道编码(Esl 事件按此路由,改动前须确认 FS 侧配置同步)
     */
    private String channelCode;

    /**
     * 媒体通道提供方:FS / AICC / FS_MOCK
     */
    private String mediaProvider;

    /**
     * 服务号码
     */
    private String serviceNumber;

    /**
     * 归属工作空间
     */
    private Long workspaceId;

    /**
     * 绑定的 Agent
     */
    private Long agentId;

    /**
     * 回调验签密钥:仅回显 "__SET__"(已配置)或 null(未配置),不回传密文
     */
    private String callbackSecret;

    /**
     * 每分钟回调限流
     */
    private Integer rateLimit;

    /**
     * 状态(0启用 1停用)
     */
    private String status;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

}
