package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 通话主记录(V1),对应表 {@code agent_cc_call},唯一键 (media_provider, call_id) 兜底回调重放。
 *
 * <p>字段语义与 DDL 注释一一对应,几个关键字段口径:
 * status 走 8 态呼叫状态机(见 {@link CsCallStatus});
 * conversationId / ticketId 由 V2 CallBusinessBinder 回填,是通话与文本域的关联主键;
 * callerNumber / ani 落库前统一 PII 脱敏;recordingOssKey 只存对象键,不存云侧 URL。</p>
 *
 * <p>扩展方向:外呼方向记录(outbound)、IVR 节点路径明细、客户画像标签
 * 都按可空列追加,不动既有列;唯一键保证同一供应商呼叫标识全局只有一行。</p>
 */
@Data
@TableName("agent_cc_call")
public class CsCall {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AICC 呼叫标识,业务幂等键。 */
    private String callId;
    /** 供应商标识:AICC_MOCK(dev/test) / AICC。 */
    private String mediaProvider;
    /** 语音渠道编码,同时是文本域 agent_cs_channel 的同码渠道。 */
    private String channelCode;
    private Long workspaceId;
    private Long agentId;
    /** 映射客服会话(V2 CallBusinessBinder 回填)。 */
    private Long conversationId;
    /** 映射工单(V2 转人工时回填)。 */
    private Long ticketId;
    /** 方向:inbound / outbound(外呼扩展位)。 */
    private String direction;
    /** 主叫号码,PII 脱敏后存储。 */
    private String callerNumber;
    private String calleeNumber;
    private String ani;
    private String dnis;
    /** 呼叫状态:RINGING/ANSWERED/BOT_SERVING/WAITING_AGENT/AGENT_SERVING/HOLD/WRAP_UP/ENDED。 */
    private String status;
    /** 是否经过机器人自助('0'/'1'),bot_resolve_rate 的口径字段。 */
    private String botHandled;
    /** AICC 坐席工号。 */
    private String workNo;
    private Date ringAt;
    private Date answerAt;
    private Date hangupAt;
    /** 通话时长(秒),CDR 事件补写。 */
    private Integer durationSec;
    /** 计费时长(秒),CDR 事件补写。 */
    private Integer billSec;
    private String hangupCause;
    private String hangupBy;
    private Integer sipCode;
    /** 录音存储对象键(oss 或 local-fs 同一规则),不存云侧 URL。 */
    private String recordingOssKey;
    private String recordingId;
    private Date recordingReadyAt;
    /** 留存截止时间,CallRetentionTask 据此清理。 */
    private Date recordingRetainUntil;
    /** IVR 路径(扩展位)。 */
    private String ivrPath;
    /** 按键序列,IVR 多级菜单按序拼接。 */
    private String dtmfDigits;
    /** 转人工来源/目标:AICC 侧为技能组或坐席字符串。 */
    private String transferFrom;
    private String transferTo;
    private Date createTime;
    private Date updateTime;
}
