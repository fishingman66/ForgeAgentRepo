package com.forgeagent.callcenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 不允许打断词组(C2):ASR partial 命中即豁免 barge-in,防止语气词掐断话术。
 * 精确(partial 全等)/模糊(partial 包含)双模式,对齐 SmartCall ivr_answer_no_interrupt。
 */
@Data
@TableName("agent_cc_voice_no_interrupt")
public class CsVoiceNoInterrupt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 词组(如 嗯/哦/好的)。 */
    private String word;

    /** exact=partial 全等 / fuzzy=partial 包含。 */
    private String matchMode;

    private String enabled;

    /** 归属空间,0=全局。 */
    private Long workspaceId;

    private Long delFlag;

    private Date createTime;

    private Date updateTime;
}
