package com.forgeagent.agent.service;

import com.forgeagent.agent.service.AgentRunner.RunnerContext.ChatTurn;
import com.forgeagent.agent.service.AgentRunner.RunnerContext;
import com.forgeagent.agent.service.runner.ToolAssembler;
import com.forgeagent.common.core.utils.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 执行器接口
 *
 * <p>按 RunnerContext 是否具备 LLM 调用条件选择实现：
 * 具备 baseUrl/apiKey/model 时由 {@code AgentScopeRunner} 走 AgentScope 内核执行,
 * 否则回退 {@code OpenAiCompatRunner}/{@code MockAgentRunner}。</p>
 *
 * @author ForgeAgent
 */
public interface AgentRunner {

    /**
     * 时区说明(恒定文本,追加到系统提示词)
     *
     * <p>注意:本行内容固定不变,不会破坏供应商侧的 KV Cache 前缀缓存。
     * 当前具体时间不由提示词提供,后续通过时间工具(工具调用)获取,避免动态内容进入上下文。</p>
     */
    static String timeZonePrompt() {
        return "时区说明:本系统运行于北京时间(UTC+8)。回答涉及日期、时间、时区换算时,一律以北京时间为准,"
            + "当前具体时间必须通过 get_current_time 工具查询,不要凭推测给出日期或时刻。";
    }

    /**
     * 是否支持该上下文(实现间互斥,服务层按顺序取第一个支持的)
     */
    default boolean supports(RunnerContext ctx) {
        return true;
    }

    /**
     * 执行一轮对话并返回回复与真实用量
     *
     * @param ctx     执行上下文
     * @param content 用户消息
     * @return 助手回复与用量(真实用量缺失时由服务层估算)
     */
    RunnerResult reply(RunnerContext ctx, String content);

    /**
     * 流式增量回调:每收到一段增量文本调用一次
     */
    interface StreamListener {
        void onDelta(String delta);

        /**
         * 团队接力:某一棒开始执行
         *
         * @param index     棒序号(0 基)
         * @param total     总棒数
         * @param agentName 当前棒 Agent 名称
         * @param role      角色(member / leader)
         */
        default void onTeamAgentStart(int index, int total, String agentName, String role) {
        }

        /**
         * 团队接力:某一棒增量文本片段
         *
         * @param index 棒序号
         * @param delta 增量文本
         */
        default void onTeamAgentDelta(int index, String delta) {
        }

        /**
         * 团队接力:某一棒执行完成
         *
         * @param index       棒序号
         * @param content     该棒完整输出
         * @param totalTokens 该棒真实总用量(缺失为 null)
         */
        default void onTeamAgentDone(int index, String content, Integer totalTokens) {
        }
    }

    /**
     * 流式执行一轮对话:增量文本片段经 listener 回调,最终返回与 reply 一致的结果
     *
     * <p>默认实现为降级:整段调用 {@link #reply} 后一次性回调全部文本。
     * 团队协作与结构化输出模式同样降级为整段回调。</p>
     */
    default RunnerResult replyStream(RunnerContext ctx, String content, StreamListener listener) {
        RunnerResult result = reply(ctx, content);
        if (result != null && StringUtils.isNotBlank(result.content())) {
            listener.onDelta(result.content());
        }
        return result;
    }

    /**
     * 送入模型的图片引用(base64 不带 data URI 前缀)
     *
     * @param mediaType 媒体类型(image/png、image/jpeg)
     * @param base64    图片原始字节的 base64 编码(不含 data URI 前缀)
     */
    record ImageRef(String mediaType, String base64) {
    }

    /**
     * 执行上下文
     *
     * @param agentName        Agent 名称
     * @param modelName        模型配置名称
     * @param toolNames        绑定工具名(展示用)
     * @param knowledgeNames   绑定知识库名(展示用)
     * @param retrievedContext 知识库检索命中内容(已按相关度排序拼接,空串表示未命中)
     * @param systemPrompt     系统提示词
     * @param history          会话历史(升序,不含本轮用户消息)
     * @param llmBaseUrl       模型 API 基础地址(OpenAI 兼容)
     * @param llmApiKey        模型 API 密钥
     * @param llmModel         模型标识(如 deepseek-flash)
     * @param temperature      采样温度
     * @param maxTokens        最大输出 tokens
     * @param sessionId        会话ID(工具审批与轮级中断定位用)
     * @param agentId          AgentID(审批单关联)
     * @param roundId          请求轮次ID(审批单关联)
     * @param toolDefs         平台工具定义(经审批门装配进内核 Toolkit)
     * @param mcpDefs          MCP 服务器定义(全部工具经审批门装配进内核 Toolkit)
     * @param tokenBudget      Agent 累计 Token 预算(空为不限)
     * @param usedTokens       发起本轮前已落库的累计用量(空为 0)
     * @param outputSchema     结构化输出 JSON Schema 字符串(空为关闭)
     * @param teamMembers      团队协作成员定义(空为单 Agent 执行;成员先行处理,主 Agent 最后综合)
     * @param images           本轮用户消息携带的图片(空为纯文本,图片 base64 已在此处编码好)
     * @param attachments      本轮用户消息携带的文件附件(空为无文件;供 read_attachment 工具按需读取)
     * @param sqlCollector     问数 SQL 执行明细收集器(空表示不收集;工具执行时写入)
     */
    record RunnerContext(String agentName, String modelName, String toolNames, String knowledgeNames,
                         String retrievedContext, String systemPrompt, List<ChatTurn> history,
                         String llmBaseUrl, String llmApiKey, String llmModel,
                         Double temperature, Integer maxTokens,
                         Long sessionId, Long agentId, String roundId,
                         List<ToolAssembler.ToolDef> toolDefs,
                         List<ToolAssembler.McpDef> mcpDefs,
                         Long tokenBudget, Long usedTokens,
                         String outputSchema, List<TeamMember> teamMembers, String teamMode,
                         List<ImageRef> images, List<AttachmentRef> attachments,
                         List<Map<String, Object>> sqlCollector) {

        /**
         * 协作模式(P2 / C2):sequential / parallel / reviewer / supervisor
         */
        public String teamModeOrDefault() {
            return StringUtils.isBlank(teamMode) ? "sequential" : teamMode;
        }

        /**
         * 会话消息
         */
        public record ChatTurn(String role, String content) {
        }

        /**
         * 文件附件引用(供 read_attachment 工具按需读取临时索引)
         *
         * @param attachmentId 附件ID
         * @param name         原始文件名
         * @param knowledgeId  临时索引知识库ID
         * @param pageCount    页数/工作表数
         * @param summary      文件摘要/大纲
         */
        public record AttachmentRef(Long attachmentId, String name, Long knowledgeId, Integer pageCount,
                                    String summary) {
        }

        /**
         * 团队协作成员定义(成员不带工具,仅模型+提示词)
         *
         * @param agentId    成员 AgentID
         * @param agentName  成员名称
         * @param sysPrompt  成员系统提示词
         * @param model      成员模型配置(空则回退主 Agent 模型卡)
         */
        public record TeamMember(Long agentId, String agentName, String sysPrompt, MemberModel model,
                                 boolean allowTools) {

            /** 兼容旧调用方(成员默认不带工具) */
            public TeamMember(Long agentId, String agentName, String sysPrompt, MemberModel model) {
                this(agentId, agentName, sysPrompt, model, false);
            }

            /**
             * 成员模型卡(为空表示回退主 Agent 模型)
             */
            public record MemberModel(String baseUrl, String apiKey, String modelName,
                                      Double temperature, Integer maxTokens) {
            }
        }

        /**
         * LLM 调用条件是否齐备
         */
        public boolean llmReady() {
            return StringUtils.isNotBlank(llmBaseUrl)
                && StringUtils.isNotBlank(llmApiKey)
                && StringUtils.isNotBlank(llmModel);
        }

        /**
         * 是否为团队协作执行
         */
        public boolean isTeam() {
            return teamMembers != null && !teamMembers.isEmpty();
        }

        /**
         * 是否启用结构化输出
         */
        public boolean isStructured() {
            return StringUtils.isNotBlank(outputSchema);
        }

        /**
         * 兼容旧调用方(无工具装配)的构造器
         */
        public RunnerContext(String agentName, String modelName, String toolNames, String knowledgeNames,
                             String retrievedContext, String systemPrompt, List<ChatTurn> history,
                             String llmBaseUrl, String llmApiKey, String llmModel,
                             Double temperature, Integer maxTokens) {
            this(agentName, modelName, toolNames, knowledgeNames, retrievedContext, systemPrompt, history,
                llmBaseUrl, llmApiKey, llmModel, temperature, maxTokens, null, null, null, List.of(), List.of(),
                null, null, null, List.of(), "sequential", List.of(), List.of(),
                new CopyOnWriteArrayList<>());
        }

        /**
         * 兼容第三步前调用方(无预算参数)的构造器
         */
        public RunnerContext(String agentName, String modelName, String toolNames, String knowledgeNames,
                             String retrievedContext, String systemPrompt, List<ChatTurn> history,
                             String llmBaseUrl, String llmApiKey, String llmModel,
                             Double temperature, Integer maxTokens,
                             Long sessionId, Long agentId, String roundId,
                             List<ToolAssembler.ToolDef> toolDefs,
                             List<ToolAssembler.McpDef> mcpDefs) {
            this(agentName, modelName, toolNames, knowledgeNames, retrievedContext, systemPrompt, history,
                llmBaseUrl, llmApiKey, llmModel, temperature, maxTokens,
                sessionId, agentId, roundId, toolDefs, mcpDefs, null, null, null, List.of(),
                "sequential", List.of(), List.of(), new CopyOnWriteArrayList<>());
        }
    }

    /**
     * 执行结果
     *
     * @param content          助手回复
     * @param promptTokens     真实输入 tokens(未获取时为 null)
     * @param completionTokens 真实输出 tokens(未获取时为 null)
     * @param totalTokens      真实总 tokens(未获取时为 null)
     * @param runner           执行器标识(mock / openai-compat)
     * @param budgetExceeded   本轮是否因 Token 预算超限被终止
     * @param structured       回复是否为结构化输出(受 outputSchema 约束的 JSON)
     * @param teamTrace        团队协作各成员执行轨迹(单 Agent 为空)
     */
    record RunnerResult(String content, Integer promptTokens, Integer completionTokens,
                        Integer totalTokens, String runner, boolean budgetExceeded,
                        boolean structured, List<TeamTraceLine> teamTrace) {

        public RunnerResult(String content, Integer promptTokens, Integer completionTokens,
                            Integer totalTokens, String runner) {
            this(content, promptTokens, completionTokens, totalTokens, runner, false, false, List.of());
        }

        public RunnerResult(String content, Integer promptTokens, Integer completionTokens,
                            Integer totalTokens, String runner, boolean budgetExceeded) {
            this(content, promptTokens, completionTokens, totalTokens, runner, budgetExceeded, false, List.of());
        }

        /**
         * 团队协作轨迹行
         *
         * @param agentId     成员 AgentID(主 Agent 为空)
         * @param agentName   成员名称
         * @param roleLabel   角色标识(member / leader)
         * @param content     该棒输出
         * @param totalTokens 该棒真实总用量(缺失为 null)
         */
        public record TeamTraceLine(Long agentId, String agentName, String roleLabel,
                                    String content, Integer totalTokens) {
        }
    }
}
