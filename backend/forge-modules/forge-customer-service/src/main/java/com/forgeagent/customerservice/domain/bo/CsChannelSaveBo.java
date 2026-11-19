package com.forgeagent.customerservice.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.customerservice.domain.CsChannel;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 接入渠道业务对象(运营台新建/编辑;channelSecret 编辑留空表示不修改)
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsChannel.class, reverseConvertGenerate = false)
public class CsChannelSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "渠道ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 渠道编码(唯一业务键)
     */
    @NotBlank(message = "渠道编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "渠道编码不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String channelCode;

    /**
     * 渠道类型(web/wechat/api)
     */
    @NotBlank(message = "渠道类型不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 20, message = "渠道类型不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String channelType;

    /**
     * 渠道密钥(编辑留空=不修改)
     */
    @NotBlank(message = "渠道密钥不能为空", groups = {AddGroup.class})
    private String channelSecret;

    /**
     * 知识库 ID(逗号分隔)
     */
    @Size(max = 500, message = "知识库ID串不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String knowledgeIds;

    /**
     * CORS 白名单(逗号分隔)
     */
    @Size(max = 500, message = "CORS白名单不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String corsAllowlist;

    /**
     * 会话模式(single/multi)
     */
    @Size(max = 20, message = "会话模式不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String sessionMode;

    /**
     * 兜底应答
     */
    @Size(max = 500, message = "兜底应答不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String fallbackReply;

    /**
     * 兜底应答(英文)
     */
    @Size(max = 500, message = "英文兜底应答不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String fallbackReplyEn;

    /**
     * 转人工策略(zero_hit/keyword/always)
     */
    @Size(max = 20, message = "转人工策略不能超过 20 字符", groups = {AddGroup.class, EditGroup.class})
    private String handoffPolicy;

    /**
     * 转人工关键词(逗号分隔)
     */
    @Size(max = 500, message = "转人工关键词不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String handoffKeywords;

    /**
     * 转人工规则(JSON)
     */
    @Size(max = 2000, message = "转人工规则不能超过 2000 字符", groups = {AddGroup.class, EditGroup.class})
    private String handoffRules;

    /**
     * 身份绑定配置(C2 护栏;空=不做写操作护栏)
     */
    @Size(max = 500, message = "身份绑定不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String identityBinding;

    /**
     * 归属空间
     */
    private Long workspaceId;

    /**
     * 绑定 Agent
     */
    private Long agentId;

    /**
     * 渠道属主
     */
    private Long ownerUserId;

    /**
     * 频率限制(次/分钟)
     */
    private Integer rateLimit;

    /**
     * 状态(0启用 1停用)
     */
    private String status;

    /**
     * 应答语言
     */
    @Size(max = 10, message = "语言不能超过 10 字符", groups = {AddGroup.class, EditGroup.class})
    private String locale;
}
