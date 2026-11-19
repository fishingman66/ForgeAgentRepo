package com.forgeagent.callcenter.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.callcenter.domain.CsCallChannelConfig;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * 语音渠道 upsert 业务对象(运营维护入口)。
 *
 * <p>按 channelCode 做业务唯一键,不带 id 即新增、带 id 即更新基础字段;
 * callbackSecret / voiceConfigJson 刻意不收:密钥走配置加密,媒体参数由 SQL/初始化维护。</p>
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = CsCallChannelConfig.class, reverseConvertGenerate = false)
public class CsCallChannelConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填;新增留空)
     */
    @NotNull(message = "渠道ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 渠道编码(唯一键;ESL 事件按此路由,改动前须确认 FS 侧配置同步)
     */
    @NotBlank(message = "渠道编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "渠道编码不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String channelCode;

    /**
     * 媒体通道提供方(白名单校验:FS / AICC / FS_MOCK)
     */
    @NotBlank(message = "媒体通道提供方不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "媒体通道提供方不能超过 32 字符", groups = {AddGroup.class, EditGroup.class})
    private String mediaProvider;

    /**
     * 服务号码
     */
    @Size(max = 64, message = "服务号码不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String serviceNumber;

    /**
     * 归属工作空间
     */
    @NotNull(message = "工作空间不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long workspaceId;

    /**
     * 绑定的 Agent
     */
    @NotNull(message = "AgentID不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long agentId;

    /**
     * 状态(0启用 1停用);新增留空按启用处理
     */
    @Pattern(regexp = "^$|0|1", message = "状态仅允许 0(启用)/1(停用)", groups = {AddGroup.class, EditGroup.class})
    private String status;

}
