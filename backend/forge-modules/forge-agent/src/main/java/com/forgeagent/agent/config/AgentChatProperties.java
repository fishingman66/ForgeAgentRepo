package com.forgeagent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 会话图片附件配置
 *
 * @author ForgeAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.chat")
public class AgentChatProperties {

    /**
     * 图片存储目录
     */
    private String imageDir = "./data/agent-chat-images";

    /**
     * 单条消息图片数上限
     */
    private Integer imageMaxCount = 4;

    /**
     * 单张图片大小上限(MB)
     */
    private Long imageMaxSizeMb = 5L;

    /**
     * 允许的扩展名(逗号分隔)
     */
    private String imageAllowedExt = "png,jpg,jpeg";

    /**
     * 缩略图长边像素
     */
    private Integer imageThumbSize = 320;

    /**
     * 文件存储目录
     */
    private String fileDir = "./data/agent-chat-files";

    /**
     * 单条消息文件数上限(含图片)
     */
    private Integer attachmentMaxCount = 4;

    /**
     * 单个文件大小上限(MB),需与 spring.servlet.multipart.max-file-size 保持一致
     */
    private Long fileMaxSizeMb = 10L;

    /**
     * 允许的文件扩展名(逗号分隔)
     */
    private String fileAllowedExt = "pdf,docx,xlsx,csv,txt,md,json,html";

    /**
     * 小文件全文注入的 token 上限,超过则只注入摘要与检索片段
     */
    private Integer fileInlineTokens = 2000;

    /**
     * 单轮附件内容注入的 token 预算上限
     */
    private Integer attachmentContextBudget = 3000;

    // ---------------- P3 / M1:记忆体系六层化(L0 预算 / L1 工作记忆 / L2 会话摘要) ----------------

    /** L0:单轮上下文窗口总预算(tokens),含系统提示词/记忆/检索/附件/历史,不含模型输出预留 */
    private Integer contextWindowTokens = 16000;

    /** L0:为模型输出预留的 tokens(绑定模型声明了 maxTokens 时优先取模型值) */
    private Integer contextOutputReserve = 2000;

    /** L0:无论动态块占用多少,会话历史至少保留的 tokens */
    private Integer historyMinTokens = 512;

    /** L2:会话历史条数上限(兜底,主要按 token 预算裁剪) */
    private Integer historyMaxTurns = 40;

    /** L2:每轮从库里预加载的最大消息条数(按 id 倒序,供预算裁剪与查询改写共用) */
    private Integer historyPreloadLimit = 80;

    /** L2:是否启用异步滚动摘要 */
    private Boolean summaryEnabled = true;

    /** L2:未摘要消息累计达到该 token 数才触发一次摘要,避免短会话频繁调模型 */
    private Integer summaryTriggerTokens = 1500;

    /** L2:始终保留为原始消息、不参与摘要的最近消息条数 */
    private Integer summaryKeepRecentMessages = 4;

    /** L2:摘要文本最大字符数 */
    private Integer summaryMaxChars = 800;

    /** L1:是否启用工具输出工作记忆压缩 */
    private Boolean workingMemoryEnabled = true;

    /** L1:工具结果超过该 token 数才压缩进工作记忆 */
    private Integer workingMemoryThresholdTokens = 800;

    /** L1:压缩占位文本里保留的原文开头字符数 */
    private Integer workingMemoryPreviewChars = 200;

    /** L1:单条工具结果入工作记忆的最大字符数(超过则不压缩,保持原样) */
    private Integer workingMemoryMaxChars = 200000;

    /** L1:单个 run 最多压缩的工具结果条数 */
    private Integer workingMemoryMaxEntries = 20;

    /** L1:工作记忆条目 TTL(秒) */
    private Integer workingMemoryTtlSeconds = 3600;

    /** L1:recall_working_memory 单次返回的最大字符数 */
    private Integer workingMemoryRecallLimitChars = 4000;
}
